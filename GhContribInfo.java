///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS org.kohsuke:github-api:1.330
//DEPS info.picocli:picocli:4.7.6

import org.kohsuke.github.GHCommit;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.logging.Level;

@Command(name = "gh-contrib-info", mixinStandardHelpOptions = true, version = "1.0", description = """
        Collects information from GitHub about contributors to a given list of repositories.
        """)
public class GhContribInfo implements Callable<Void> {

    static {
        System.setProperty(
                "java.util.logging.SimpleFormatter.format",
                "%4$-7s [%3$s] %5$s %6$s%n"
        );
    }

    private static final java.util.logging.Logger log = java.util.logging.Logger
            .getLogger(GhContribInfo.class.getName());

    @CommandLine.Option(names = {"-l", "--limit"},
            description = "Max number of commits to display for each contributor/repository. Only the latest commits are displayed.",
            defaultValue = "2", showDefaultValue = Help.Visibility.ALWAYS)
    private Integer commitLimit;

    @CommandLine.Option(names = {"-i", "--include"},
            description = "Contributors to include. If set, only explicitly mentioned contributors will be listed.")
    private Set<String> contributors;

    @CommandLine.Option(names = {"-f", "--include-from"},
            description = "File listing contributors to include, one username per line. If set, only explicitly mentioned contributors will be listed. Set to '-' to read from stdin.")
    private Set<String> contributorList;

    @CommandLine.Option(names = {"-o", "--output"},
            description = "File to send the output to, in csv format, overwriting the file if it exists.")
    private Path outputFile;

    @CommandLine.Option(names = {"-d", "--debug"},
            description = "Enable debug output.",
            defaultValue = "false", showDefaultValue = Help.Visibility.ALWAYS)
    private boolean debug;

    @Parameters(index = "0..*", description = "The repositories to check; format: <org>/<repo>", arity = "1..*")
    private Set<String> repositories;

    Map<String, GitHubUserInfo> gitHubUserInfoByLogin = new TreeMap<>();

    public static void main(String[] args) {
        int exitCode = new CommandLine(new GhContribInfo()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Void call() throws Exception {
        if (debug) {
            log.setLevel(Level.FINEST);
        }
        log.log(Level.INFO, "repositories: {0}", repositories);

        GitHub client = GitHubBuilder.fromEnvironment().fromPropertyFile().build();

        Set<String> contributorsToInclude = null;
        if (contributors != null && !contributors.isEmpty() || contributorList != null && !contributorList.isEmpty()) {
            contributorsToInclude = new HashSet<>();
            if (contributors != null) {
                contributorsToInclude.addAll(contributors);
            }
            if (contributorList != null) {
                for (var pathString : contributorList) {
                    contributorsToInclude.addAll(readLinesContributorsFromFile(pathString));
                }
            }
            log.log(Level.INFO, "contributors: {0}", contributorsToInclude);

            // Make sure to at least display a '0' contribution count for every single contributor requested explicitly.
            for (String login : contributorsToInclude) {
                gitHubUserInfoByLogin.computeIfAbsent(login, GitHubUserInfo::from);
            }
        }

        int count = 0;
        for (String repositoryName : repositories) {
            log.log(Level.INFO, "inspecting {0}", repositoryName);
            var repo = client.getRepository(repositoryName);
            Set<String> remainingContributorsToInclude = contributorsToInclude == null ? null : new HashSet<>(contributorsToInclude);
            for (var contributor : repo.listContributors()) {
                var login = contributor.getLogin();

                log.log((count % 10 == 0) ? Level.INFO : Level.FINEST, "inspecting {0} -- contributor {1}", new Object[]{repositoryName, login});
                ++count;

                if (remainingContributorsToInclude == null || remainingContributorsToInclude.remove(login)) {
                    var userInfo = gitHubUserInfoByLogin.computeIfAbsent(login, GitHubUserInfo::from);
                    var contribInfo = GitHubUserContributionInfo.from(contributor);
                    userInfo.contributionByRepository.put(repositoryName, contribInfo);
                    int i = 0;
                    for (var commit : repo.queryCommits().author(login).list().withPageSize(commitLimit)) {
                        log.log(Level.FINEST, "inspecting {0} -- contributor {1} -- commmit {2}", new Object[]{repositoryName, login, commit.getSHA1()});
                        contribInfo.commits.add(CommitInfo.from(commit));
                        i++;
                        if (i >= commitLimit) {
                            break;
                        }
                    }
                }

                if (remainingContributorsToInclude != null && remainingContributorsToInclude.isEmpty()) {
                    break;
                }
            }
        }

        Format.MARKDOWN.print(System.out, this);

        if (outputFile != null) {
            try (var out = new PrintStream(outputFile.toFile())) {
                Format.CSV.print(out, this);
            }
            System.out.println();
            System.out.printf("Output written to %s in CSV format.", outputFile);
        }

        return null;
    }

    private List<String> readLinesContributorsFromFile(String pathString) throws IOException {
        if (pathString.equals("-")) {
            pathString = "/dev/stdin";
        }
        var path = Path.of(pathString);
        return Files.readAllLines(path);
    }

    record GitHubUserInfo(String login, Map<String, GitHubUserContributionInfo> contributionByRepository) {
        public static GitHubUserInfo from(String login) {
            return new GitHubUserInfo(login, new TreeMap<>());
        }
    }

    record GitHubUserContributionInfo(int contributions, List<CommitInfo> commits) {
        public static GitHubUserContributionInfo from(GHRepository.Contributor contributor) {
            return new GitHubUserContributionInfo(contributor.getContributions(), new ArrayList<>());
        }
    }

    record CommitInfo(URL url, Instant timestamp) {
        public static CommitInfo from(GHCommit commit) throws IOException {
            return new CommitInfo(commit.getHtmlUrl(), commit.getCommitDate().toInstant());
        }
    }


    enum Format {
        MARKDOWN {
            @Override
            public void print(PrintStream out, GhContribInfo info) {
                for (var gitHubUser : info.gitHubUserInfoByLogin.values()) {
                    out.printf("## GitHub user: %s\n", gitHubUser.login);

                    if (gitHubUser.contributionByRepository.isEmpty()) {
                        out.printf("NO CONTRIBUTION FOUND\n");
                    } else {
                        for (var entry : gitHubUser.contributionByRepository.entrySet()) {
                            var repo = entry.getKey();
                            var contrib = entry.getValue();
                            out.printf("- %s: %s\n", repo, contrib.contributions);
                            if (!contrib.commits.isEmpty()) {
                                for (var commit : contrib.commits) {
                                    out.printf("  %s %s\n", commit.timestamp, commit.url.toExternalForm());
                                }
                            }
                        }
                    }
                }
            }
        },
        CSV {
            @Override
            public void print(PrintStream out, GhContribInfo info) {
                for (var gitHubUser : info.gitHubUserInfoByLogin.values()) {
                    int contributions = 0;
                    CommitInfo latest = null;

                    for (var entry : gitHubUser.contributionByRepository.entrySet()) {
                        var contrib = entry.getValue();
                        contributions += contrib.contributions;
                        for (var commit : contrib.commits) {
                            latest = (latest == null || latest.timestamp.isBefore(commit.timestamp)) ? commit : latest;
                        }
                    }
                    out.printf(
                            "\"%s\",\"%s\",\"%s\"\n",
                            gitHubUser.login,
                            contributions,
                            latest == null ? null : latest.timestamp,
                            latest == null ? null : latest.url.toExternalForm()
                    );
                }
            }
        };

        public abstract void print(PrintStream out, GhContribInfo info);
    }

}