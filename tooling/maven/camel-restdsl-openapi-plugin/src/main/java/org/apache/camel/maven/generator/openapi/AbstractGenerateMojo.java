/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.maven.generator.openapi;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.camel.generator.openapi.DestinationGenerator;
import org.apache.camel.util.IOHelper;
import org.apache.camel.util.ObjectHelper;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.twdata.maven.mojoexecutor.MojoExecutor;
import org.yaml.snakeyaml.inspector.TagInspector;
import org.yaml.snakeyaml.nodes.Tag;

import static org.apache.commons.lang3.StringUtils.isNotEmpty;
import static org.twdata.maven.mojoexecutor.MojoExecutor.artifactId;
import static org.twdata.maven.mojoexecutor.MojoExecutor.configuration;
import static org.twdata.maven.mojoexecutor.MojoExecutor.executeMojo;
import static org.twdata.maven.mojoexecutor.MojoExecutor.executionEnvironment;
import static org.twdata.maven.mojoexecutor.MojoExecutor.goal;
import static org.twdata.maven.mojoexecutor.MojoExecutor.groupId;
import static org.twdata.maven.mojoexecutor.MojoExecutor.plugin;
import static org.twdata.maven.mojoexecutor.MojoExecutor.version;

abstract class AbstractGenerateMojo extends AbstractMojo {

    // this list should be in priority order
    public static final String[] DEFAULT_REST_CONSUMER_COMPONENTS = new String[] {
            "platform-http", "servlet", "jetty", "undertow", "netty-http", "coap" };

    @Parameter
    String apiContextPath;

    @Parameter
    String destinationGenerator;

    @Parameter
    String destinationToSyntax;

    @Parameter
    String filterOperation;

    @Parameter
    String modelNamePrefix;

    @Parameter
    String modelNameSuffix;

    @Parameter(defaultValue = "${project.build.directory}/generated-sources/openapi")
    String modelOutput;

    @Parameter
    String modelPackage;

    @Parameter(defaultValue = "false")
    String modelWithXml;

    @Parameter(defaultValue = "${project}")
    MavenProject project;

    @Parameter(defaultValue = "true")
    boolean restConfiguration;

    @Parameter(defaultValue = "false")
    boolean clientRequestValidation;

    @Parameter(defaultValue = "false")
    boolean skip;

    @Parameter(defaultValue = "${project.basedir}/src/spec/openapi.json", required = true)
    String specificationUri;

    @Parameter(name = "auth")
    String auth;

    @Parameter
    String basePath;

    @Parameter(defaultValue = "3.0.54")
    String swaggerCodegenMavenPluginVersion;

    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject mavenProject;

    @Parameter(defaultValue = "${session}", readonly = true)
    private MavenSession mavenSession;

    @Component
    private BuildPluginManager pluginManager;

    @Parameter
    private Map<String, String> configOptions;

    DestinationGenerator createDestinationGenerator() throws MojoExecutionException {
        final Class<DestinationGenerator> destinationGeneratorClass;

        final ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        final URL outputDirectory;
        try {
            outputDirectory = new File(project.getBuild().getOutputDirectory()).toURI().toURL();
        } catch (final MalformedURLException e) {
            throw new IllegalStateException(e);
        }
        final URL[] withOutput = new URL[] { outputDirectory };

        try (URLClassLoader classLoader = new URLClassLoader(withOutput, contextClassLoader)) {
            @SuppressWarnings({ "unchecked", "rawtypes" })
            final Class<DestinationGenerator> tmp = (Class) classLoader.loadClass(destinationGenerator);

            if (!DestinationGenerator.class.isAssignableFrom(tmp)) {
                throw new MojoExecutionException(
                        "The given destinationGenerator class (" + destinationGenerator
                                                 + ") does not implement " + DestinationGenerator.class.getName()
                                                 + " interface.");
            }

            destinationGeneratorClass = tmp;
        } catch (final ClassNotFoundException | IOException e) {
            throw new MojoExecutionException(
                    "The given destinationGenerator class (" + destinationGenerator
                                             + ") cannot be loaded, make sure that it is present in the COMPILE classpath scope of the project",
                    e);
        }

        final DestinationGenerator destinationGeneratorObject;
        try {
            destinationGeneratorObject = destinationGeneratorClass.getDeclaredConstructor().newInstance();
        } catch (InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
            throw new MojoExecutionException(
                    "The given destinationGenerator class (" + destinationGenerator
                                             + ") cannot be instantiated, make sure that it is declared as public and that all dependencies are present on the COMPILE classpath scope of the project",
                    e);
        }
        return destinationGeneratorObject;
    }

    void generateDto(final String language) throws MojoExecutionException {
        getLog().info(
                "Generating DTO classes using io.swagger.codegen.v3:swagger-codegen-maven-plugin:"
                      + swaggerCodegenMavenPluginVersion);

        // swagger-codegen-maven-plugin documentation and its supported options
        // https://github.com/swagger-api/swagger-codegen/tree/3.0.0/modules/swagger-codegen-maven-plugin

        final List<MojoExecutor.Element> elements = new ArrayList<>();
        elements.add(new MojoExecutor.Element("inputSpec", specificationUri));
        elements.add(new MojoExecutor.Element("language", language));
        elements.add(new MojoExecutor.Element("generateApis", "false"));
        elements.add(new MojoExecutor.Element("generateModelTests", "false"));
        elements.add(new MojoExecutor.Element("generateModelDocumentation", "false"));
        elements.add(new MojoExecutor.Element("generateSupportingFiles", "false"));
        if (modelOutput != null) {
            elements.add(new MojoExecutor.Element("output", modelOutput));
        }
        if (modelPackage != null) {
            elements.add(new MojoExecutor.Element("modelPackage", modelPackage));
        }
        if (modelNamePrefix != null) {
            elements.add(new MojoExecutor.Element("modelNamePrefix", modelNamePrefix));
        }
        if (modelNameSuffix != null) {
            elements.add(new MojoExecutor.Element("modelNameSuffix", modelNameSuffix));
        }
        if (modelWithXml != null) {
            elements.add(new MojoExecutor.Element("withXml", modelWithXml));
        }
        if (configOptions == null) {
            configOptions = new HashMap<>(1);
        }
        /* workaround for https://github.com/swagger-api/swagger-codegen/issues/11797
         * with the next release jakarta=true should be used
         * https://github.com/swagger-api/swagger-codegen-generators/pull/1131
         */
        configOptions.put("hideGenerationTimestamp", "true");
        elements.add(new MojoExecutor.Element(
                "configOptions", configOptions.entrySet().stream()
                        .map(e -> new MojoExecutor.Element(e.getKey(), e.getValue()))
                        .toArray(MojoExecutor.Element[]::new)));

        executeMojo(
                plugin(
                        groupId("io.swagger.codegen.v3"),
                        artifactId("swagger-codegen-maven-plugin"),
                        version(swaggerCodegenMavenPluginVersion)),
                goal("generate"),
                configuration(
                        elements.toArray(new MojoExecutor.Element[0])),
                executionEnvironment(
                        mavenProject,
                        mavenSession,
                        pluginManager));
    }

    protected String detectCamelVersionFromClasspath() {
        return mavenProject.getDependencies().stream().filter(
                d -> "org.apache.camel".equals(d.getGroupId()) && ObjectHelper.isNotEmpty(d.getVersion()))
                .findFirst().map(Dependency::getVersion).orElse(null);
    }

    protected String detectRestComponentFromClasspath() {
        for (final Dependency dep : mavenProject.getDependencies()) {
            if ("org.apache.camel".equals(dep.getGroupId()) || "org.apache.camel.springboot".equals(dep.getGroupId())) {
                final String aid = dep.getArtifactId();
                final Optional<String> comp = Arrays.stream(DEFAULT_REST_CONSUMER_COMPONENTS)
                        .filter(c -> aid.startsWith("camel-" + c)).findFirst();
                if (comp.isPresent()) {
                    return comp.get();
                }
            }
        }
        return null;
    }

    protected boolean detectSpringBootFromClasspath() {
        return mavenProject.getDependencies().stream().anyMatch(d -> "org.springframework.boot".equals(d.getGroupId()));
    }

    protected String detectSpringBootMainPackage() throws IOException {
        for (final String src : mavenProject.getCompileSourceRoots()) {
            final String d = findSpringSpringBootPackage(new File(src));
            if (d != null) {
                return d;
            }
        }
        return null;
    }

    protected String findSpringSpringBootPackage(final File dir) throws IOException {
        final File[] files = dir.listFiles();
        if (files != null) {
            for (final File file : files) {
                if (file.getName().endsWith(".java")) {
                    try (InputStream stream = new FileInputStream(file)) {
                        final String content = IOHelper.loadText(stream);
                        if (content.contains("@SpringBootApplication")) {
                            return grabPackageName(content);
                        }
                    }
                } else if (file.isDirectory()) {
                    final String packageName = findSpringSpringBootPackage(file);
                    if (packageName != null) {
                        return packageName;
                    }
                }
            }
        }
        return null;
    }

    protected static String grabPackageName(final String content) {
        final String[] lines = content.split("\\n");
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("package ")) {
                line = line.substring(8);
                line = line.trim();
                if (line.endsWith(";")) {
                    line = line.substring(0, line.length() - 1);
                }
                return line;
            }
        }
        return null;
    }

    protected String findAppropriateComponent() {
        String comp = detectRestComponentFromClasspath();
        if (comp != null) {
            getLog().info("Detected Camel Rest component from classpath: " + comp);
        } else {
            comp = "platform-http";

            String gid = "org.apache.camel";
            String aid = "camel-platform-http";

            // is it spring boot?
            if (detectSpringBootFromClasspath()) {
                gid = "org.apache.camel.springboot";
                aid = "camel-platform-http-starter";
            }

            String dep = "\n\t\t<dependency>"
                         + "\n\t\t\t<groupId>" + gid + "</groupId>"
                         + "\n\t\t\t<artifactId>" + aid + "</artifactId>";
            String ver = detectCamelVersionFromClasspath();
            if (ver != null) {
                dep += "\n\t\t\t<version>" + ver + "</version>";
            }
            dep += "\n\t\t</dependency>\n";

            getLog().info("Cannot detect Rest component from classpath. Will use platform-http as Rest component.");
            getLog().info("Add the following dependency in the Maven pom.xml file:\n" + dep + "\n");
        }

        return comp;
    }

    private URL inputSpecRemoteUrl(String specificationUri) {
        try {
            return new URI(specificationUri).toURL();
        } catch (URISyntaxException | MalformedURLException | IllegalArgumentException e) {
            return null;
        }
    }

    private Map<String, String> parse(String urlEncodedAuthStr) {
        Map<String, String> auths = new HashMap<>();
        if (isNotEmpty(urlEncodedAuthStr)) {
            String[] parts = urlEncodedAuthStr.split(",");
            for (String part : parts) {
                String[] kvPair = part.split(":");
                if (kvPair.length == 2) {
                    auths.put(URLDecoder.decode(kvPair[0], StandardCharsets.UTF_8),
                            URLDecoder.decode(kvPair[1], StandardCharsets.UTF_8));
                }
            }
        }
        return auths;
    }

    final class TrustedTagInspector implements TagInspector {
        @Override
        public boolean isGlobalTagAllowed(Tag tag) {
            return true;
        }
    }
}
