/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.smithy.cli.commands;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import software.amazon.smithy.build.model.SmithyBuildConfig;
import software.amazon.smithy.cli.ArgumentReceiver;
import software.amazon.smithy.cli.Arguments;
import software.amazon.smithy.cli.CliError;
import software.amazon.smithy.cli.Command;
import software.amazon.smithy.cli.HelpPrinter;
import software.amazon.smithy.cli.StandardOptions;
import software.amazon.smithy.cli.dependencies.DependencyResolver;
import software.amazon.smithy.cli.dependencies.ResolvedArtifact;

final class LspCommand implements Command {

    private final String parentCommandName;
    private final DependencyResolver.Factory dependencyResolverFactory;

    LspCommand(String parentCommandName, DependencyResolver.Factory dependencyResolverFactory) {
        this.parentCommandName = parentCommandName;
        this.dependencyResolverFactory = dependencyResolverFactory;
    }

    private static final class Options implements ArgumentReceiver {
        private String version = "LATEST";

        @Override
        public Consumer<String> testParameter(String name) {
            if ("--version".equals(name)) {
                return v -> version = v;
            }
            return null;
        }

        @Override
        public void registerHelp(HelpPrinter printer) {
            printer.param("--version", null, "LSP_VERSION",
                          "Provides a custom LSP version. Uses the latest version by default.");
        }
    }

    @Override
    public String getName() {
        return "lsp";
    }

    @Override
    public String getSummary() {
        return "Downloads, starts, and stops the Smithy Language Server Protocol (LSP).";
    }

    @Override
    public int execute(Arguments arguments, Env env) {
        arguments.addReceiver(new ConfigOptions());
        arguments.addReceiver(new Options());

        CommandAction action = HelpActionWrapper.fromCommand(
                this, parentCommandName, this::runLsp);

        return action.apply(arguments, env);
    }

    private int runLsp(Arguments arguments, Env env) {
        StandardOptions standardOptions = arguments.getReceiver(StandardOptions.class);
        ConfigOptions configOptions = arguments.getReceiver(ConfigOptions.class);
        SmithyBuildConfig config = configOptions.createSmithyBuildConfig();
        DependencyResolver resolver = dependencyResolverFactory.create(config, env);
        Options options = arguments.getReceiver(Options.class);

        if (!standardOptions.quiet()) {
            env.stderr().println("Checking for Smithy LSP...").flush();
        }

        DependencyHelper.addConfiguredMavenRepos(config, resolver);
        resolver.addDependency("software.amazon.smithy:smithy-language-server:" + options.version);
        List<ResolvedArtifact> resolvedArtifacts = resolver.resolve();
        List<Path> dependencies = new ArrayList<>(resolvedArtifacts.size());
        for (ResolvedArtifact artifact : resolvedArtifacts) {
            dependencies.add(artifact.getPath());
        }

        if (!standardOptions.quiet()) {
            env.stderr().println("Starting LSP at version: " + resolvedArtifacts.get(0).getVersion()).flush();
        }

        new IsolatedRunnable(dependencies, getClass().getClassLoader().getParent(), loader -> {
            try {
                Class<?> main = loader.loadClass("software.amazon.smithy.lsp.Main");
                Method method = main.getMethod("main", String[].class);
                method.invoke(null, (Object) new String[]{"0"});
            } catch (Exception e) {
                throw new CliError("Error running LSP", 1, e);
            }
        }).run();

        return 0;
    }
}
