/*
 * Copyright 2015 The Mail Importer Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package to.lean.tools.gmail.importer.gmail;

import com.google.api.client.googleapis.batch.BatchRequest;
import com.google.api.client.googleapis.batch.json.JsonBatchCallback;
import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.AbstractInputStreamContent;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.repackaged.com.google.common.base.Preconditions;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Label;
import com.google.api.services.gmail.model.ListLabelsResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.ModifyMessageRequest;
import com.google.common.base.Verify;
import com.google.common.collect.BiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import com.google.common.collect.Sets;
import com.google.common.io.ByteStreams;
import to.lean.tools.gmail.importer.local.LocalMessage;

import javax.inject.Inject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static com.google.common.base.Verify.verify;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

/**
 * Exports an API for a user's mailbox. This is a wrapper around the user's
 * Gmail mailbox as exposed by the Gmail API. This is not a perfect interface
 * as it assumes that the mailbox is static except for the actions that it
 * issues.
 */
class Mailbox {
  static final int TOO_MANY_CONCURRENT_REQUESTS_FOR_USER = 429;

  private final User user;
  private final GmailService gmailService;
  private final Logger logger;

  private List<Label> labels;
  private Map<String, Label> labelsById;
  private Map<String, Label> labelsByName;

  @Inject
  Mailbox(
      GmailServiceFactory gmailServiceFactory,
      User user,
      Logger logger) {
    this.user = user;
    this.gmailService = gmailServiceFactory.create(user);
    this.logger = logger;
  }

  void connect() throws InterruptedException {
    loadLabels();
  }

  public ImmutableList<Label> labels() throws
      GmailService.GmailServiceException {
    return ImmutableList.copyOf(labels);
  }

  public Optional<Label> getLabelById(String id) {
    return Optional.ofNullable(labelsById.get(id));
  }

  public Optional<Label> getLabelByName(String name) {
    return Optional.ofNullable(labelsByName.get(name));
  }

  public Label createLabel(String name) {
    throw new UnsupportedOperationException();
  }

  private void loadLabels() throws InterruptedException {
    if (labels != null) {
      return;
    }

    ListLabelsResponse labelResponse;
    try {
      labelResponse = gmailService.labels().get();
    } catch (ExecutionException e) {
      throw GmailService.GmailServiceException.forException(e);
    }

    verify(!labelResponse.isEmpty(), "could not get labels");

    labels = labelResponse.getLabels();
    labelsByName =
        labels.stream().collect(toMap(Label::getName, label -> label));
    labelsById = labels.stream().collect(toMap(Label::getId, label -> label));
    System.err.format("Got labels: %s", labelsByName);
  }

  Multimap<LocalMessage, Message> mapMessageIds(
      Iterable<LocalMessage> localMessages) throws InterruptedException {
    Multimap<LocalMessage, Message> results =
        MultimapBuilder.hashKeys().linkedListValues().build();

    GmailService.Batch batch = gmailService.newBatch();

    for (LocalMessage localMessage : localMessages) {
      batch.query(GmailService.Query.builder()
          .rfc822MsgId(localMessage.getMessageId())
          .setField(GmailService.Field.MESSAGE_ID)
          .build())
          .thenAccept(response -> {
            if (!response.isEmpty()) {
              results.putAll(localMessage, response.getMessages());
              logger.fine(() -> {
                String s = "For " + localMessage.getMessageId() + " got:\n";
                return s + response.getMessages().stream()
                    .map(message -> "  message id: " + message.getId() + "\n")
                    .collect(joining("\n"));
              });
            }
          });
    }
    try {
      batch.execute().get();
    } catch (ExecutionException e) {
      throw GmailService.GmailServiceException.forException(e);
    }

    return results;
  }

  void fetchExistingLabels(Iterable<Message> messages)
      throws InterruptedException {
    GmailService.Batch batch = gmailService.newBatch();

    try {
      for (Message message : messages) {
        batch.message(message.getId(), "id,labelIds")
            .thenAccept(responseMessage -> {
              Verify.verify(
                  message.getId().equals(responseMessage.getId()),
                  "Message ids must be equal %s:%s",
                  message.getId(), responseMessage.getId());
              List<String> gmailMessageIds =
                  responseMessage.getLabelIds() == null
                      ? ImmutableList.of()
                      : responseMessage.getLabelIds();
              logger.fine(() -> String.format(
                  "For message %s, got labels: %s\n",
                  responseMessage.getId(),
                  gmailMessageIds.stream()
                      .map(id -> getLabelById(id)
                          .orElse(new Label().setName(id)))
                      .map(Label::getName)
                      .collect(Collectors.joining(", "))));
              message.setLabelIds(gmailMessageIds);
            });
      }
      batch.execute().get();
    } catch (ExecutionException e) {
      throw GmailService.GmailServiceException.forException(e);
    }
  }

  Message uploadMessage(LocalMessage localMessage)
      throws InterruptedException {

    try {
      return gmailService.importMessage(
          new AbstractInputStreamContent("message/rfc822") {
            byte[] bytes = localMessage.getRawContent();

            @Override
            public InputStream getInputStream() throws IOException {
              return new ByteArrayInputStream(bytes);
            }

            @Override
            public long getLength() throws IOException {
              return bytes.length;
            }

            @Override
            public boolean retrySupported() {
              return true;
            }
          }).get();
    } catch (ExecutionException e) {
      throw GmailService.GmailServiceRequestException.forException(e);
    }
  }

  void syncLocalLabelsToGmail(Multimap<LocalMessage, Message> map) {
    class Batches {
      GmailService.Batch thisBatch;
      GmailService.Batch nextBatch;
    }
    Batches batches = new Batches();
    batches.thisBatch = gmailService.newBatch();
    batches.nextBatch = gmailService.newBatch();

    try {
      for (Map.Entry<LocalMessage, Message> entry : map.entries()) {
        LocalMessage localMessage = entry.getKey();
        Message message = entry.getValue();

        Set<String> labelNamesToAdd = localMessage.getFolders().stream()
            .map(this::normalizeLabelName)
            .collect(toSet());
        Set<String> labelNamesToRemove = Sets.newHashSet("SPAM", "TRASH");
        labelNamesToRemove.removeAll(labelNamesToAdd);

        if (localMessage.isStarred()) {
          labelNamesToAdd.add("STARRED");
          labelNamesToRemove.remove("STARRED");
        }
        if (localMessage.isUnread()) {
          labelNamesToAdd.add("UNREAD");
          labelNamesToRemove.remove("UNREAD");
        } else {
          labelNamesToRemove.add("UNREAD");
          labelNamesToAdd.remove("UNREAD");
        }

        if (!labelNamesToAdd.contains("INBOX")) {
          labelNamesToRemove.add("INBOX");
          labelNamesToAdd.remove("INBOX");
        }

        List<String> labelIdsToAdd = labelNamesToAdd.stream()
            .map(labelName ->
                getLabelByName(labelName).get().getId())
            .collect(toList());
        List<String> labelIdsToRemove = labelNamesToRemove.stream()
            .map(labelName ->
                getLabelByName(labelName).get().getId())
            .collect(toList());
/*
        CompletableFuture<Message> future =
            new Retrier<Message>(() ->
                    batches.thisBatch.modify(
                        message.getId(), labelIdsToAdd, labelIdsToRemove))
                .future();

        JsonBatchCallback<Message> callback = new JsonBatchCallback<Message>() {
          @Override
          public void onFailure(
              GoogleJsonError e, HttpHeaders responseHeaders)
              throws IOException {
            System.err.format("For message: %s, got error: %s\n",
                message.getId(), e.toPrettyString());
            if (e.getCode() == TOO_MANY_CONCURRENT_REQUESTS_FOR_USER) {
              request.queue(batches.nextBatch, this);
            }
          }

          @Override
          public void onSuccess(
              Message message, HttpHeaders responseHeaders)
              throws IOException {
            System.err.println(message.toPrettyString());
          }
        };
        request.queue(batches.thisBatch, callback);
        */
      }
/*
      while (batches.thisBatch.size() > 0) {
        batches.thisBatch.execute();
        batches.thisBatch = batches.nextBatch;
        batches.nextBatch = gmail.batch();
      }
    } catch (IOException e) {
      throw new RuntimeException(e); */
    } finally {}
  }

  String normalizeLabelName(String localLabel) {
    if (localLabel.equalsIgnoreCase("INBOX")) {
      return "INBOX";
    }
    if (localLabel.equalsIgnoreCase("DRAFTS")) {
      return "Import/Drafts";
    }
    if (localLabel.equalsIgnoreCase("TRASH")) {
      return "TRASH";
    }
    if (localLabel.equalsIgnoreCase("SPAM")) {
      return "SPAM";
    }
    return localLabel;
  }

  private void syncLabels(
      Gmail gmailApi,
      BiMap<String, String> labelIdToNameMap,
      Multimap<LocalMessage, Message> localMessageToGmailMessages)
      throws IOException {
    BatchRequest relabelBatch = gmailApi.batch();
    for (Map.Entry<LocalMessage, Message> entry
        : localMessageToGmailMessages.entries()) {
      LocalMessage localMessage = entry.getKey();
      Message gmailMessage = entry.getValue();

      Set<String> gmailLabels = gmailMessage.getLabelIds() == null
          ? ImmutableSet.of()
          : gmailMessage.getLabelIds().stream()
              .map(labelIdToNameMap::get)
              .collect(Collectors.toSet());

      List<String> missingLabelIds = localMessage.getFolders().stream()
          .map(folder -> folder.equalsIgnoreCase("Inbox") ? "INBOX" : folder)
          .filter(folder -> !gmailLabels.contains(folder))
          .map(folder -> labelIdToNameMap.inverse().get(folder))
          .collect(Collectors.toList());

      if (localMessage.isUnread() && !gmailLabels.contains("UNREAD")) {
        missingLabelIds.add("UNREAD");
      }
      if (localMessage.isStarred() && !gmailLabels.contains("STARRED")) {
        missingLabelIds.add("STARRED");
      }

      for (String folder : localMessage.getFolders()) {
        if (!gmailLabels.contains(folder)) {
          System.out.format("Trying to add labels %s to %s\n",
              missingLabelIds.stream()
                  .map(labelIdToNameMap::get)
                  .collect(Collectors.joining(", ")),
              gmailMessage.getId());
          gmailApi.users().messages().modify(
              user.getEmailAddress(),
              gmailMessage.getId(),
              new ModifyMessageRequest().setAddLabelIds(missingLabelIds))
              .queue(relabelBatch, new JsonBatchCallback<Message>() {
                @Override
                public void onFailure(
                    GoogleJsonError e, HttpHeaders responseHeaders)
                    throws IOException {
                  System.err.format("For label ids %s, got error: %s\n",
                      missingLabelIds, e.toPrettyString());
                }

                @Override
                public void onSuccess(
                    Message message, HttpHeaders responseHeaders)
                    throws IOException {
                  System.out.format("Successfully added labels %s to %s\n",
                      missingLabelIds.stream()
                          .map(labelIdToNameMap::get)
                          .collect(Collectors.joining(", ")),
                      message.getId());
                }
              });
        }
      }
      if (relabelBatch.size() > 0) {
        relabelBatch.execute();
      }
    }
  }

  private void createMissingLabels(
      Gmail gmailApi,
      final BiMap<String, String> labelIdToNameMap,
      Set<LocalMessage> localMessages)
      throws IOException {

    Set<String> missingLabels = localMessages.stream()
        .flatMap(localMessage -> localMessage.getFolders().stream())
        .filter(folder -> !"INBOX".equalsIgnoreCase(folder))
        .filter(folder -> !labelIdToNameMap.containsValue(folder))
        .collect(Collectors.toSet());

    if (!missingLabels.isEmpty()) {
      BatchRequest batchRequest = gmailApi.batch();
      for (String label : missingLabels) {
        System.err.format("Adding label %s\n", label);
        gmailApi.users()
            .labels()
            .create(user.getEmailAddress(), new Label()
                .setName(label)
                .setLabelListVisibility("labelHide")
                .setMessageListVisibility("show"))
            .queue(batchRequest, new JsonBatchCallback<Label>() {
              @Override
              public void onFailure(
                  GoogleJsonError e, HttpHeaders responseHeaders)
                  throws IOException {
                System.err.format("For label %s, got error: %s\n",
                    label, e.toPrettyString());
              }

              @Override
              public void onSuccess(
                  Label label, HttpHeaders responseHeaders) throws IOException {
                labelIdToNameMap.put(label.getId(), label.getName());
              }
            });
      }
      batchRequest.execute();
    }
  }

  private static class Retrier<T>
      implements BiFunction<T, Throwable, T> {
    private final Supplier<CompletableFuture<T>> futureFactory;

    Retrier(Supplier<CompletableFuture<T>> futureFactory) {
      this.futureFactory = futureFactory;
    }

    @Override
    public T apply(T result, Throwable throwable) {
      if (throwable
          instanceof GmailService.GmailServiceRequestException) {
        GmailService.GmailServiceRequestException requestException =
            (GmailService.GmailServiceRequestException) throwable;
        if (requestException.getGoogleJsonError().getCode()
            == TOO_MANY_CONCURRENT_REQUESTS_FOR_USER) {

        }
      }
      throw new CompletionException(throwable);
    }

    public CompletableFuture<T> future() {
      return futureFactory.get().handleAsync(this);
    }
  }
}
