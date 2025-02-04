///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS org.kohsuke:github-api:1.123
//DEPS info.picocli:picocli:4.7.6
//DEPS com.squareup.okhttp3:okhttp:4.12.0

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.stream.Collectors;

import org.kohsuke.github.GHCommit;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help;
import picocli.CommandLine.Parameters;

@Command(name = "gh-author-info", mixinStandardHelpOptions = true, version = "1.1", description = """
        Collects information from GitHub about authors of a given list of commits.
        """)
public class GhAuthorInfo implements Callable<Void> {

    static {
        System.setProperty("java.util.logging.SimpleFormatter.format",
                "%4$-7s [%3$s] %5$s %6$s%n");

    }

    private static final java.util.logging.Logger log = java.util.logging.Logger
            .getLogger(GhAuthorInfo.class.getName());

    @CommandLine.Option(names = { "-l", "--limit" },
            description = "Max number of commits to inspect for each author, additionally to the given commits. Set to 0 to only inspect the given commits.",
            defaultValue = "100", showDefaultValue = Help.Visibility.ALWAYS)
    private Integer commitLimit;

    @CommandLine.Option(names = { "-o", "--output" },
            description = "File to send the output to, in csv format, overwriting the file if it exists.")
    private Path outputFile;

    @CommandLine.Option(names = { "-f", "--full" },
            description = "Full output, including commits that we couldn't find extra information for.",
            defaultValue = "false", showDefaultValue = Help.Visibility.ALWAYS)
    private boolean outputEvenIfNoExtraInfo;

    @Parameters(index = "0", description = "The repository to check; format: <org>/<repo>")
    private String repository;

    @Parameters(index = "1..*", description = "The commits to check, given as SHAs.", arity = "1..*")
    private List<String> commits;

    Map<String, GitHubUserInfo> gitHubUserInfoByLogin = new LinkedHashMap<>();
    Map<String, CommitInfo> commitInfoBySha = new LinkedHashMap<>();

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
            var commitInfo = getOrCreateCommitInfo(commit);
            var author = commit.getAuthor();
            if (author == null) {
                log.log(Level.WARNING, "not able to process {0}, because no GitHub user is bound to it", sha);
                continue;
            }
            var gitHubUser = getOrCreateGitHubUserInfo(repo, author);
            gitHubUser.commits.put(commit.getSHA1(), commitInfo);
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

    private GitHubUserInfo getOrCreateGitHubUserInfo(GHRepository repo, GHUser user) {
        return gitHubUserInfoByLogin.computeIfAbsent(user.getLogin(), login -> {
            var result = GitHubUserInfo.from(user);

            if (commitLimit == 0) {
                return result;
            }
            try {
                log.log(Level.INFO, "fetching commits by {0} (max {1})", new Object[] { login, commitLimit });
                int processedCount = 0;
                for (var commit : repo.queryCommits().author(login).pageSize(Math.min(100, commitLimit)).list()) {
                    result.commits.put(commit.getSHA1(), getOrCreateCommitInfo(commit));
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
        });
    }

    private CommitInfo getOrCreateCommitInfo(GHCommit commit) {
        return commitInfoBySha.computeIfAbsent(commit.getSHA1(), sha -> CommitInfo.from(commit));
    }

    private static <T> void addIfNotNull(Set<T> set, T value) {
        if (value != null) {
            set.add(value);
        }
    }

    record GitHubUserInfo(String login, NameAndEmail gitHubInfo, Map<String, CommitInfo> commits) {
        public static GitHubUserInfo from(GHUser user) {
            return new GitHubUserInfo(user.getLogin(), NameAndEmail.from(user), new LinkedHashMap<>());
        }

        public Set<NameAndEmail> allNameAndEmails() {
            Set<NameAndEmail> result = new LinkedHashSet<>();
            addIfNotNull(result, gitHubInfo);
            for (CommitInfo commit : commits.values()) {
                addIfNotNull(result, commit.gitAuthor);
            }
            return result;
        }

        public Set<CommitInfo> authoredCommits(Collection<String> shas) {
            Set<CommitInfo> result = new LinkedHashSet<>();
            for (String sha : shas) {
                var commit = commits.get(sha);
                if (commit != null) {
                    result.add(commit);
                }
            }
            return result;
        }

        public Set<NameAndEmail> extraNameAndEmails(Collection<String> ignoredShas) {
            Set<NameAndEmail> result = new LinkedHashSet<>();
            addIfNotNull(result, gitHubInfo);
            for (CommitInfo commit : commits.values()) {
                if (!ignoredShas.contains(commit.sha)) {
                    addIfNotNull(result, commit.gitAuthor);
                }
            }
            return result;
        }
    }

    record CommitInfo(String sha, NameAndEmail gitAuthor) {
        public static CommitInfo from(GHCommit commit) {
            return new CommitInfo(commit.getSHA1(), NameAndEmail.from(commit));
        }
    }

    record NameAndEmail(String name, String email) {
        public static NameAndEmail from(GHUser user) {
            try {
                var name = user.getName();
                var email = user.getEmail();
                if (name == null && email == null) {
                    return null;
                }
                return new NameAndEmail(name == null ? "<no name>" : name, email == null ? "<no email>" : email);
            } catch (IOException e) {
                throw new UncheckedIOException("Exception while retrieving author info for " + user.getLogin(), e);
            }
        }

        public static NameAndEmail from(GHCommit commit) {
            try {
                var author = commit.getCommitShortInfo().getAuthor();
                var name = author == null ? null : author.getName();
                var email = author == null ? null : author.getEmail();
                if (name == null && email == null) {
                    return null;
                }
                return new NameAndEmail(name == null ? "<no name>" : name, email == null ? "<no email>" : email);
            } catch (IOException e) {
                throw new UncheckedIOException("Exception while retrieving author info for " + commit.getSHA1(), e);
            }
        }
    }

    enum Format {
        MARKDOWN {
            @Override
            public void print(PrintStream out, GhAuthorInfo info) {
                Set<String> remainingShas = new LinkedHashSet<>(info.commits);
                for (var gitHubUser : info.gitHubUserInfoByLogin.values()) {
                    var nameAndEmails = gitHubUser.allNameAndEmails();
                    if (!info.outputEvenIfNoExtraInfo && nameAndEmails.size() == 1) {
                        // Skip
                        continue;
                    }
                    out.printf("## GitHub user: %s\n", gitHubUser.login);
                    out.printf("Authored commits:\n");
                    for (CommitInfo commit : gitHubUser.authoredCommits(info.commits)) {
                        remainingShas.remove(commit.sha);
                        out.printf("\t%s %s %s\n", commit.sha, commit.gitAuthor.name, commit.gitAuthor.email);
                    }
                    out.printf("Names and emails found from Git/GitHub:\n");
                    if (nameAndEmails.isEmpty()) {
                        out.printf("\tNone.\n");
                    } else {
                        for (NameAndEmail nameAndEmail : nameAndEmails) {
                            out.printf("\t%s %s\n", nameAndEmail.name, nameAndEmail.email);
                        }
                    }
                }
                out.printf("## Commits without extra info from GitHub\n");
                if (remainingShas.isEmpty()) {
                    out.printf("None.\n");
                } else {
                    for (String sha : remainingShas) {
                        CommitInfo commit = info.commitInfoBySha.get(sha);
                        out.printf("%s %s %s\n", sha, commit.gitAuthor.name, commit.gitAuthor.email);
                    }
                }
            }
        },
        CSV {
            @Override
            public void print(PrintStream out, GhAuthorInfo info) {
                out.printf("\"GitHub login\",\"Names/emails from GitHub\",\"SHAs\"\n");
                Set<String> remainingShas = new LinkedHashSet<>(info.commits);
                for (var gitHubUser : info.gitHubUserInfoByLogin.values()) {
                    var nameAndEmails = gitHubUser.allNameAndEmails();
                    if (nameAndEmails.size() == 1) {
                        // Skip
                        continue;
                    }
                    out.printf("\"%s\",\"%s\",\"%s\"\n",
                            gitHubUser.login,
                            nameAndEmails.stream()
                                    .map(nameAndEmail -> "%s %s".formatted(nameAndEmail.name, nameAndEmail.email))
                                    .collect(Collectors.joining(";")),
                            gitHubUser.authoredCommits(info.commits).stream().map(commit -> commit.sha)
                                    .collect(Collectors.joining(";")));
                }
                if (info.outputEvenIfNoExtraInfo && !remainingShas.isEmpty()) {
                    for (String sha : remainingShas) {
                        CommitInfo commit = info.commitInfoBySha.get(sha);
                        out.printf("\"%s\",\"%s\",\"%s\"\n",
                                "<unknown>",
                                "%s %s".formatted(commit.gitAuthor.name, commit.gitAuthor.email),
                                sha);
                    }
                }
            }
        };

        public abstract void print(PrintStream out, GhAuthorInfo info);
    }

}