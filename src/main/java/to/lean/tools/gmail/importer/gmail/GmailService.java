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

import com.google.api.services.gmail.model.Label;
import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * A user-oriented facade over {@link com.google.api.services.gmail.Gmail}.
 */
interface GmailService {

  /**
   * Returns a list of all labels in the user's account.
   */
  ImmutableList<Label> labels() throws GmailServiceException;

  /** Returns the {@code Label} for the given {@code id}. */
  Optional<Label> getLabelById(String id);

  /**
   * Returns the {@code Label} for the given {@code name}.
   *
   * @throws NoSuchElementException if there is no such label.
   */
  Optional<Label> getLabelByName(String name);

  /**
   * Creates a new label with the given name.
   *
   * @return the new {@code Label}
   */
  Label createLabel(String name);

  class GmailServiceException extends RuntimeException {
    public GmailServiceException(IOException e) {
      super(e);
    }

    public GmailServiceException(String message) {
      super(message);
    }

    public static GmailServiceException forException(IOException e) {
      // TODO(flan): Split this into different exceptions
      return new GmailServiceException(e);
    }
  }
}
