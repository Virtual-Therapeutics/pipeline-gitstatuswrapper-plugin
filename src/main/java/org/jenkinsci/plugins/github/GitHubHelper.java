/*
MIT License

Copyright (c) 2019 Zachary Sherwin

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
 */
package org.jenkinsci.plugins.github;

import com.cloudbees.plugins.credentials.common.UsernamePasswordCredentials;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Run;
import hudson.plugins.git.util.BuildData;
import jenkins.plugins.git.AbstractGitSCMSource;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMRevisionAction;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.SCMSourceOwner;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.credentials.CredentialsHelper;
import org.jenkinsci.plugins.github.util.BuildDataHelper;
import org.jenkinsci.plugins.github_branch_source.GitHubSCMSource;
import org.jenkinsci.plugins.github_branch_source.PullRequestSCMRevision;
import org.kohsuke.github.GHCommit;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.net.Proxy;

public class GitHubHelper {

  public static final String DEFAULT_GITHUB_API_URL = "https://api.github.com";

  public static final String CREDENTIALS_ID_NOT_EXISTS = "The credentialsId does not seem to exist, please check it";
  public static final String NULL_CREDENTIALS_ID = "Credentials ID is null or empty";
  public static final String CREDENTIALS_LOGIN_INVALID = "The supplied credentials are invalid to login";
  public static final String INVALID_REPO = "The specified repository does not exist for the specified account";
  public static final String INVALID_COMMIT = "The specified commit does not exist in the specified repository";

  public static final String UNABLE_TO_INFER_DATA = "Unable to infer git data, please specify repo, credentialsId, account and sha values";
  public static final String UNABLE_TO_INFER_COMMIT = "Could not infer exact commit to use, please specify one";
  public static final String UNABLE_TO_INFER_CREDENTIALS_ID = "Can not infer exact credentialsId to use, please specify one";

  public static GitHub getGitHubIfValid(String credentialsId, @Nonnull String gitApiUrl,
      Proxy proxy, Item context) throws IOException {
    if (credentialsId == null || credentialsId.isEmpty()) {
      throw new IllegalArgumentException(NULL_CREDENTIALS_ID);
    }
    UsernamePasswordCredentials credentials = CredentialsHelper
        .getCredentials(UsernamePasswordCredentials.class, credentialsId, context);
    if (credentials == null) {
      throw new IllegalArgumentException(CREDENTIALS_ID_NOT_EXISTS);
    }
    GitHubBuilder githubBuilder = new GitHubBuilder();

    githubBuilder
        .withOAuthToken(credentials.getPassword().getPlainText(), credentials.getUsername());

    githubBuilder = githubBuilder.withProxy(proxy);
    githubBuilder = githubBuilder.withEndpoint(gitApiUrl);

    GitHub github = githubBuilder.build();

    if (github.isCredentialValid()) {
      return github;
    } else {
      throw new IllegalArgumentException(CREDENTIALS_LOGIN_INVALID);
    }
  }

  public static GHRepository getRepoIfValid(String credentialsId, String gitApiUrl, Proxy proxy,
      String account, String repo, Item context) throws IOException {
    GitHub github = getGitHubIfValid(credentialsId, gitApiUrl, proxy, context);
    GHRepository repository = github.getUser(account).getRepository(repo);
    if (repository == null) {
      throw new IllegalArgumentException(INVALID_REPO);
    }
    return repository;
  }

  public static GHCommit getCommitIfValid(String credentialsId, String gitApiUrl, Proxy proxy,
      String account, String repo, String sha, Item context) throws IOException {
    GHRepository repository = getRepoIfValid(credentialsId, gitApiUrl, proxy, account, repo,
        context);
    GHCommit commit = repository.getCommit(sha);
    if (commit == null) {
      throw new IllegalArgumentException(INVALID_COMMIT);
    }
    return commit;
  }

  public static String inferBuildRepo(Run<?, ?> run) throws IOException {
    return getRemoteData(run, 4).replace(".git", "");
  }

  public static String inferBuildAccount(Run<?, ?> run) throws IOException {
    return getRemoteData(run, 3);
  }

  private static String getRemoteData(Run<?, ?> run, Integer index) throws IOException {
    BuildData data = run.getAction(BuildData.class);
    if (data != null && data.getRemoteUrls() != null && !data.getRemoteUrls().isEmpty()) {
      String remoteUrl = data.remoteUrls.iterator().next();
      return remoteUrl.split("\\/")[index];
    }

    throw new IOException("Unable to infer git repo from build data");
  }


  public static String inferBuildCommitSHA1(Run<?, ?> run) throws IOException {
    SCMRevisionAction action = run.getAction(SCMRevisionAction.class);
    if (action != null) {
      SCMRevision revision = action.getRevision();
      if (revision instanceof AbstractGitSCMSource.SCMRevisionImpl) {
        return ((AbstractGitSCMSource.SCMRevisionImpl) revision).getHash();
      } else if (revision instanceof PullRequestSCMRevision) {
        return ((PullRequestSCMRevision) revision).getPullHash();
      } else {
        throw new IllegalArgumentException(UNABLE_TO_INFER_COMMIT);
      }
    } else {
      try {
        return BuildDataHelper.getCommitSHA1(run).name();
      } catch (IOException e) {
        throw new IllegalArgumentException(UNABLE_TO_INFER_COMMIT, e);
      }
    }
  }

  public static String inferBuildCredentialsId(Run<?, ?> run) {
    try {
      String credentialsID = getSource(run).getCredentialsId();
      if (credentialsID != null) {
        return credentialsID;
      } else {
        throw new IllegalArgumentException(UNABLE_TO_INFER_CREDENTIALS_ID);
      }
    } catch (IllegalArgumentException e) {
      //TODO: Find a way to get the credentials used by CpsScmFlowDefinition
      throw e;
    }
  }

  private static GitHubSCMSource getSource(Run<?, ?> run) {
    ItemGroup parent = run.getParent().getParent();
    if (parent instanceof SCMSourceOwner) {
      SCMSourceOwner owner = (SCMSourceOwner) parent;
      for (SCMSource source : owner.getSCMSources()) {
        if (source instanceof GitHubSCMSource) {
          return ((GitHubSCMSource) source);
        }
      }
      throw new IllegalArgumentException(UNABLE_TO_INFER_DATA);
    } else {
      throw new IllegalArgumentException(UNABLE_TO_INFER_DATA);
    }
  }
}
