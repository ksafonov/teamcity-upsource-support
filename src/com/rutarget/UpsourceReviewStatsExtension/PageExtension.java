/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.rutarget.UpsourceReviewStatsExtension;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jetbrains.buildServer.vcs.SVcsModification;
import jetbrains.buildServer.web.openapi.ChangeDetailsExtension;
import jetbrains.buildServer.web.openapi.PagePlaces;
import jetbrains.buildServer.web.openapi.PlaceId;
import jetbrains.buildServer.web.openapi.PluginDescriptor;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.web.util.HtmlUtils;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.text.MessageFormat;
import java.util.Map;

@SuppressWarnings("UnusedDeclaration")
public class PageExtension extends ChangeDetailsExtension {

  private static final Proxy PROXY = null;//new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 8888));

  private static final String UPSOURCE_API_URL = "http://0.0.0.0:8081/~rpc/"; // TODO
  private static final String UPSOURCE_REVIEW_PUBLIC_URL = "https://0.0.0.0/{0}/review/{1}"; // TODO

  private static final String UPSOURCE_USERNAME = ""; // TODO
  private static final String UPSOURCE_PASSWORD = ""; // TODO

  private static final String REPO = ""; // TODO

  private static final int REVIEW_OPEN = 1;
  private static final int REVIEW_CLOSED = 2;

  private static final Gson GSON = new GsonBuilder().create();

  public PageExtension(PagePlaces pagePlaces, PluginDescriptor descriptor) {
    super(pagePlaces, PlaceId.CHANGE_DETAILS_BLOCK, "upsource", descriptor.getPluginResourcesPath("upsourceInfo.jsp"));
    register();
  }

  @Override
  public void fillModel(@NotNull Map<String, Object> model, @NotNull HttpServletRequest request) {
    SVcsModification modification = findVcsModification(request);
    assert modification != null;
    String version = modification.getVersion();
    String upsourceProjectId = getUpsourceProjectId(modification);
    if (upsourceProjectId == null) {
      model.put("reviewExists", false);
      return;
    }

    RevisionInProjectDTO revisionInProject = new RevisionInProjectDTO(upsourceProjectId, modification.getVersion());
    try {
      // second call to workaround https://youtrack.jetbrains.com/issue/UP-5059
      RevisionDescriptorDTO revisionInfo = request("getRevisionInfo", revisionInProject, RevisionDescriptorDTO.class);
      ReviewDescriptorDTO reviewInfo = revisionInfo != null ? revisionInfo.reviewInfo : null;
      if (reviewInfo != null) {
        reviewInfo = request("getReviewDetails", reviewInfo.reviewId, ReviewDescriptorDTO.class);
        model.put("reviewExists", true);
        model.put("reviewId", reviewInfo.reviewId.reviewId);
        model.put("reviewClosed", reviewInfo.state == REVIEW_CLOSED);
        model.put("reviewTitle", reviewInfo.title);
        model.put("reviewLink", MessageFormat.format(UPSOURCE_REVIEW_PUBLIC_URL, upsourceProjectId, reviewInfo.reviewId.reviewId));
      }
      else {
        model.put("reviewExists", false);
      }
    }
    catch (Exception e) {
      model.put("error", true);
      model.put("errorText", e.getMessage());
    }
  }

  @Nullable
  private static String getUpsourceProjectId(SVcsModification modification) {
    String url = modification.getVcsRoot().getProperties().get("url");
    if (REPO.equals(url)) {
      return "RT";
    }
    return null;
  }

  private static <T> T request(String method, @Nullable Object parameter, Class<T> clazz) throws IOException {
    String address = UPSOURCE_API_URL + method;
    URL url = new URL(address);
    @SuppressWarnings("ConstantConditions")
    HttpURLConnection c = (HttpURLConnection) (PROXY != null ? url.openConnection(PROXY) : url.openConnection());
    String authString = UPSOURCE_USERNAME + ":" + UPSOURCE_PASSWORD;
    //Base64 doesn't look thread safe, therefore we create new instance for each occasion
    c.setRequestProperty("Authorization", "Basic " + new String(new Base64().encode(authString.getBytes())));

    if (parameter != null) {
      String output = GSON.toJson(parameter);
      c.setDoOutput(true);
      c.setRequestMethod("POST");
      c.setRequestProperty("Content-Type", "application/json");
      c.setRequestProperty("Content-Length", String.valueOf(output.length()));
      c.getOutputStream().write(output.getBytes("UTF-8"));
    }
    InputStream inputStream = c.getInputStream();
    String response = IOUtils.toString(inputStream);
    try {
      JsonObject element = (JsonObject) new JsonParser().parse(response);
      return GSON.fromJson(element.get("result"), clazz);
    }
    catch (Exception e) {
      throw new IOException("Failed to parse response " + escapeToHtmlAttribute(response) + ": " + e.getMessage());
    }
  }

  private static String escapeToHtmlAttribute(String s) {
    return StringUtils.replaceEach(s,
      new String[]{"&", "<", ">", "\"", "'", "/"},
      new String[]{"&amp;", "&lt;", "&gt;", "&quot;", "&#x27;", "&#x2F;"});
  }

  private static class ProjectIdDTO {
    public String projectId;

    private ProjectIdDTO(String projectId) {
      this.projectId = projectId;
    }
  }

  private static class RevisionDescriptorDTO {
    public String revisionId;
    public long revisionDate;
    public long effectiveRevisionDate;
    public String revisionCommitMessage;
    public String state; // RevisionStateEnum really
    public String revisionIdShort;
    public String revisionProblemMessage;
    public String authorId;
    public String[] branchHeadLabel;
    public String[] parentRevisions;
    public String[] childRevisions;
    public ReviewDescriptorDTO reviewInfo;
    private String[] tags;
  }

  private static class ReviewDescriptorDTO {
    public ReviewIdDTO reviewId;
    public String title;
    public ParticipantInReviewDTO[] participants;
    public int state;
    public boolean unread;
    public int priority;
    public String branch;
    public IssueInfoDTO issue;
    public boolean canCreateIssue;
  }

  private static class ReviewIdDTO {
    public String projectId;
    public String reviewId;
  }

  private static class ParticipantInReviewDTO {
  }

//  private enum PriorityClassEnum {
//    @SerializedName("1")
//    ReadyToClose,
//    @SerializedName("2")
//    ToReview,
//    @SerializedName("3")
//    Authored,
//    @SerializedName("4")
//    None
//  }

  private static class IssueInfoDTO {

  }

  private static class ProjectListDTO {
    public ProjectInfoDTO[] project;
  }

  private static class ProjectInfoDTO {
    public String projectName;
    public String projectId;
    public String headHash;
    public String codeReviewIdPattern;
    public long lastCommitDate;
    public String lastCommitAuthorName;
    public ExternalLinkDTO[] externalLinks;
    public String projectModelType;
  }

  private static class ExternalLinkDTO {
    public String url;
    public String prefix;
  }

  private class RevisionInProjectDTO {

    public String projectId;
    public String revisionId;

    public RevisionInProjectDTO(String projectId, String revisionId) {
      this.projectId = projectId;
      this.revisionId = revisionId;
    }
  }
}
