///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS org.kohsuke:github-api:1.326
//DEPS info.picocli:picocli:4.7.6

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.logging.Level;

import org.kohsuke.github.GHCommit;
import org.kohsuke.github.GHCommitQueryBuilder;
import org.kohsuke.github.GHDirection;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHPullRequestQueryBuilder;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help;
import picocli.CommandLine.Parameters;


@Command(name = "gh-pr-contains", mixinStandardHelpOptions = true, version = "1.1", description = """
        Checks that pull requests to a given repository since a given time contain a given text, listing those that do not.
        """)
public class GhPrContains implements Callable<Void> {

    static {
        System.setProperty("java.util.logging.SimpleFormatter.format",
                "%4$-7s [%3$s] %5$s %6$s%n");
    }

    private static final java.util.logging.Logger log = java.util.logging.Logger
            .getLogger(GhPrContains.class.getName());

    @CommandLine.Option(names = { "-b", "--base" },
            description = "Base branch to consider when looking for PRs.",
            defaultValue = "main", showDefaultValue = Help.Visibility.ALWAYS)
    private String baseBranch;

    @CommandLine.Option(names = { "-c", "--commits" },
            arity = "0",
            description = "Suppress default output and list compliant commits instead of PRs, in CSV format, along with links to the corresponding PR.",
            defaultValue = "false", showDefaultValue = Help.Visibility.ALWAYS)
    private boolean commits;

    @Parameters(index = "0", description = "The repository to check; format: <org>/<repo>")
    private String repository;

    @Parameters(index = "1", description = "The date to start checking PRs from")
    private LocalDate since;

    @Parameters(index = "2", description = "The text to look for in PR descriptions")
    private String text;

    private Map<Integer, Compliance> prCompliance = new LinkedHashMap<>();

    public static void main(String[] args) {
        int exitCode = new CommandLine(new GhPrContains()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Void call() throws Exception {
        log.log(Level.INFO, "repository: {0}", repository);
        log.log(Level.INFO, "base branch: {0}", baseBranch);
        log.log(Level.INFO, "since: {0}", since);
        log.log(Level.INFO, "checking for text: {0}", text);

        GitHub client = GitHubBuilder.fromEnvironment().fromPropertyFile().build();

        var repo = client.getRepository(repository);

        if (commits) {
            listCommits(repo);
        }
        else {
            listPRs(repo);
        }

        return null;
    }

    private void listPRs(GHRepository repo) throws IOException {
        List<Integer> complyingPRs = new ArrayList<>();
        Map<String, List<Integer>> offendingPrs = new LinkedHashMap<>();
        Map<String, GHUser> offendingUsers = new LinkedHashMap<>();

        for (GHPullRequest pr : repo.queryPullRequests()
                .direction(GHDirection.DESC)
                .sort(GHPullRequestQueryBuilder.Sort.CREATED)
                .state(GHIssueState.CLOSED)
                .base(baseBranch)
                .list()) {
            try {
                var prDate = LocalDate.ofInstant(pr.getCreatedAt().toInstant(), ZoneId.systemDefault());
                if (prDate.isBefore(since)) {
                    break;
                }

                if (Compliance.COMPLIANT.equals(getCompliance(pr))) {
                    complyingPRs.add(pr.getNumber());
                } else {
                    GHUser user = pr.getUser();
                    offendingPrs.computeIfAbsent(user.getLogin(), a -> new ArrayList<>())
                            .add(pr.getNumber());
                    offendingUsers.put(user.getLogin(), user);
                }
            } catch (IOException e) {
                System.err.println("Failed to parse pull request: " + pr.getNumber());
            }
        }

        System.out.printf("Complying PRs: %s\n", complyingPRs.isEmpty() ? "None" : join(complyingPRs));
        System.out.printf("Offending users/PRs:");
        if (offendingUsers.isEmpty()) {
            System.out.printf(" None\n");
        } else {
            System.out.printf("\n");
            for (GHUser user : offendingUsers.values()) {
                System.out.printf("%s %s: %s\n", user.getEmail(), user.getLogin(),
                        join(offendingPrs.get(user.getLogin())));
            }
        }
    }

    private void listCommits(GHRepository repo) throws IOException {
        Map<String, String> complyingCommitsToPrUrl = new LinkedHashMap<>();

        for (var commit : repo.queryCommits()
                .from(baseBranch)
                .since(since.atStartOfDay().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                .list()) {
            try {
                GHPullRequest pr = getPullRequest(commit);
                if (pr == null) {
                    System.err.println("Failed to find pull request for commit: " + commit.getSHA1());
                    continue;
                }
                if (Compliance.COMPLIANT.equals(getCompliance(pr))) {
                    complyingCommitsToPrUrl.put(commit.getSHA1(), pr.getHtmlUrl().toString());
                }
            } catch (IOException e) {
                System.err.println("Failed to parse commit: " + commit.getSHA1());
            }
        }

        complyingCommitsToPrUrl.forEach((sha, prUrl) -> {
            System.out.printf("%s,%s\n", sha, prUrl);
        });
    }

    private GHPullRequest getPullRequest(GHCommit commit) throws IOException {
        for (GHPullRequest pr: commit.listPullRequests()) {
            if (!baseBranch.equals(pr.getBase().getRef())) {
                // Some merge/backport PR -- we don't care.
                continue;
            }
            return pr;
        }
        return null;
    }

    private GhPrContains.Compliance getCompliance(GHPullRequest pr) {
        return prCompliance.computeIfAbsent(pr.getNumber(), number -> {
            String body = pr.getBody();
            body = body == null ? "" : body;
            if (body.contains(text)) {
                return Compliance.COMPLIANT;
            } else {
                return Compliance.OFFENDING;
            }
        });
    }
    

    private enum Compliance {
        COMPLIANT,
        OFFENDING;
    }

    private String join(List<Integer> list) {
        return list.stream().map(number -> number.toString()).collect(Collectors.joining(", "));
    }

}