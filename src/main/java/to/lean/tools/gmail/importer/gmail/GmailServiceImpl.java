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

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Label;
import com.google.api.services.gmail.model.ListLabelsResponse;
import com.google.common.collect.ImmutableList;
import com.google.inject.assistedinject.Assisted;

import javax.annotation.concurrent.NotThreadSafe;
import javax.inject.Inject;
import javax.inject.Provider;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Supplier;

import static java.util.stream.Collectors.toMap;

/**
 * Caching implementation of {@code GmailService}. This implementation assumes
 * that it is the only client affecting the user's mailbox state.
 *
 * <p>This class is <b>not</b> thread-safe, which effectively limits processing
 * a user to a single thread.
 */
@NotThreadSafe
public class GmailServiceImpl implements GmailService {

  private final User user;
  private final Provider<Gmail> gmailApiProvider;

  private List<Label> labels;
  private Map<String, Label> labelsById;
  private Map<String, Label> labelsByName;


  @Inject
  GmailServiceImpl(@Assisted User user,
      Provider<Gmail> gmailApiProvider) {
    this.user = user;
    this.gmailApiProvider = gmailApiProvider;
  }

  @Override
  public ImmutableList<Label> labels() throws GmailServiceException {
    maybeLoadLabels();
    return ImmutableList.copyOf(labels);
  }

  @Override
  public Optional<Label> getLabelById(String id) {
    maybeLoadLabels();
    return Optional.ofNullable(labelsById.get(id));
  }

  @Override
  public Optional<Label> getLabelByName(String name) {
    maybeLoadLabels();
    return Optional.ofNullable(labelsByName.get(name));
  }

  @Override
  public Label createLabel(String name) {
    throw new UnsupportedOperationException();

  }

  private void maybeLoadLabels() {
    if (labels != null) {
      return;
    }

    ListLabelsResponse labelResponse;
    try {
      labelResponse = gmailApiProvider.get()
          .users()
          .labels()
          .list(user.getEmailAddress())
          .execute();
    } catch (IOException e) {
      throw GmailServiceException.forException(e);
    }

    verify(!labelResponse.isEmpty(), "could not get labels");

    labels = labelResponse.getLabels();
    labelsByName =
        labels.stream().collect(toMap(Label::getName, label -> label));
    labelsById = labels.stream().collect(toMap(Label::getId, label -> label));
    System.err.format("Got labels: %s", labelsByName);
  }

  private void verify(boolean expression, String message) {
    if (!expression) {
      // TODO(flan): Throw a more specific exception
      throw new GmailServiceException(message);
    }
  }

  private void verify(boolean expression, Supplier<String> messageSupplier) {
    if (!expression) {
      // TODO(flan): Throw a more specific exception
      throw new GmailServiceException(messageSupplier.get());
    }
  }

  private <T> T verifyNotNull(T o) {
    if (o == null) {
      throw new NoSuchElementException();
    }
    return o;
  }

}
