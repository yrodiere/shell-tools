///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS org.kohsuke:github-api:1.123
//DEPS info.picocli:picocli:4.7.6
//DEPS com.squareup.okhttp3:okhttp:4.12.0

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.logging.Level;

import org.kohsuke.github.GHCommit;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help;
import picocli.CommandLine.Parameters;

@Command(name = "gh-author-info", mixinStandardHelpOptions = true, version = "1.0", description = """
        Collects information from GitHub about authors of a given list of commits.
        """)
public class GhAuthorInfo implements Callable<Void> {

    static {
        System.setProperty("java.util.logging.SimpleFormatter.format",
                "%4$-7s [%3$s] %5$s %6$s%n");

    }

    private static final java.util.logging.Logger log = java.util.logging.Logger
            .getLogger(GhAuthorInfo.class.getName());

    Map<String, CollectedData> dataByAuthor = new TreeMap<>();

    @CommandLine.Option(names = { "-l", "--limit" },
             description = "Max number of commits to inspect for each author, additionally to the given commits. Set to 0 to only inspect the given commits.",
             defaultValue = "100",
             showDefaultValue = Help.Visibility.ALWAYS)
    private Integer commitLimit;

    @CommandLine.Option(names = { "-o", "--output" },
             description = "File to send the output to, in csv format, overwriting the file if it exists.")
    private Path outputFile;

    @Parameters(index = "0", description = "The repository to check; format: <org>/<repo>")
    private String repository;

    @Parameters(index = "1..*", description = "The commits to check, given as SHAs.", arity = "1..*")
    private List<String> commits;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new GhAuthorInfo()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Void call() throws Exception {
        log.log(Level.INFO, "repository: {0}", repository);
        log.log(Level.INFO, "commits: {0}", commits);

        GitHub client = GitHubBuilder.fromEnvironment().fromPropertyFile().build();

        var repo = client.getRepository(repository);

        for (String sha : commits) {
            log.log(Level.INFO, "fetching {0}", sha);
            var commit = repo.getCommit(sha);
            var author = commit.getAuthor();
            if (author == null) {
                log.log(Level.WARNING, "ignoring {0}, because no GitHub user is bound to it", sha);
                continue;
            }
            var collectedData = dataByAuthor.computeIfAbsent(author.getLogin(), ignored -> collectAuthorInfo(repo, author));
            collectedData.providedCommitSHAs.add(sha);
            collectAuthorInfo(collectedData, commit);
        }

        Format.MARKDOWN.print(System.out, dataByAuthor);

        if (outputFile != null) {
            try (var out = new PrintStream(outputFile.toFile())) {
                Format.CSV.print(out, dataByAuthor);
            }
            System.out.println();
            System.out.printf("Output written to %s in CSV format.", outputFile);
        }

        return null;
    }

    private CollectedData collectAuthorInfo(GHRepository repo, GHUser user) {
        var result = new CollectedData();

        var userInfo = AuthorInfo.from(user);
        if (userInfo != null) {
            result.authorInfo.add(userInfo);
        }

        var login = user.getLogin();
        if (commitLimit == 0) {
            return result;
        }
        try {
            log.log(Level.INFO, "fetching commits by {0} (max {1})", new Object[] { login, commitLimit });
            int processedCount = 0;
            for (var commit : repo.queryCommits().author(login).pageSize(Math.min(100, commitLimit)).list()) {
                collectAuthorInfo(result, commit);
                ++processedCount;
                if (processedCount >= commitLimit) {
                    break;
                }
            }
            log.log(Level.INFO, "processed {0} commits by {1}", new Object[] { processedCount, login });
        } catch (RuntimeException e) {
            throw new RuntimeException("Failed to collect author data for " + login, e);
        }
        return result;
    }

    private void collectAuthorInfo(CollectedData data, GHCommit commit) {
        String sha = commit.getSHA1();
        if (data.processedCommitSHAs.contains(sha)) {
            // Already processed
            return;
        }
        var commitAuthorInfo = AuthorInfo.from(commit);
        if (commitAuthorInfo != null) {
            data.authorInfo.add(commitAuthorInfo);
        }
        data.processedCommitSHAs.add(sha);
    }


    record CollectedData(Set<String> providedCommitSHAs, Set<String> processedCommitSHAs,
            Set<AuthorInfo> authorInfo) {
        public CollectedData() {
            this(new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashSet<>());
        }
    }

    record AuthorInfo(String name, String email) {
        public static AuthorInfo from(GHUser user) {
            try {
                var name = user.getName();
                var email = user.getEmail();
                if (name == null && email == null) {
                    return null;
                } 
                return new AuthorInfo(name == null ? "<no name>" : name, email == null ? "<no email>" : email);
            } catch (IOException e) {
                throw new UncheckedIOException("Exception while retrieving author info for " + user.getLogin(), e);
            }
        }
        public static AuthorInfo from(GHCommit commit) {
            try {
                var author = commit.getCommitShortInfo().getAuthor();
                var name = author == null ? null : author.getName();
                var email = author == null ? null : author.getEmail();
                if (name == null && email == null) {
                    return null;
                } 
                return new AuthorInfo(name == null ? "<no name>" : name, email == null ? "<no email>" : email);
            } catch (IOException e) {
                throw new UncheckedIOException("Exception while retrieving author info for " + commit.getSHA1(), e);
            }
        }
    }

    enum Format {
        MARKDOWN {
            @Override
            public void print(PrintStream out, Map<String, CollectedData> dataByAuthor) {
                dataByAuthor.forEach((login, collectedData) -> {
                    out.printf("## %s\n", login);
                    out.printf("Number of commits processed: %s\n", collectedData.processedCommitSHAs.size());
                    out.printf("Information found about the author:\n");
                    collectedData.authorInfo.forEach(info -> {
                        out.printf("\t%s %s\n", info.name, info.email);
                    });
                    out.printf("Relevant commits passed to this command\n");
                    collectedData.providedCommitSHAs.forEach(sha -> {
                        out.printf("\t%s\n", sha);
                    });
                });
            }
        },
        CSV {
            @Override
            public void print(PrintStream out, Map<String, CollectedData> dataByAuthor) {
                out.printf("SHA,Name,Email\n");
                dataByAuthor.forEach((login, collectedData) -> {
                    collectedData.providedCommitSHAs.forEach(sha -> {
                        collectedData.authorInfo.forEach(info -> {
                            out.printf("%s,\"%s\",\"%s\"\n", sha, info.name, info.email);
                        });
                    });
                });
            }
        };

        public abstract void print(PrintStream out, Map<String, CollectedData> dataByAuthor);
    }

}