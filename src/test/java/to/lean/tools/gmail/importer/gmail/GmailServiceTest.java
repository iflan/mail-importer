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

import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.common.util.concurrent.Uninterruptibles;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(JUnit4.class)
public class GmailServiceTest {

  @Test
  public void testSingleQueryExample() throws Exception {
    GmailService gmailService = mock(GmailService.class);
    when(gmailService.query(any(GmailService.Query.class)))
        .thenReturn(
            CompletableFuture.completedFuture(
                new ListMessagesResponse()
                    .setMessages(Collections.emptyList())));

    AtomicBoolean accepted = new AtomicBoolean(false);
    AtomicReference<Throwable> thrown = new AtomicReference<>();

    gmailService
        .query(GmailService.Query.builder()
            .rfc822MsgId("XXX@yyy")
            .setField(GmailService.Field.MESSAGE_ID)
            .build())
        .thenAccept(o -> accepted.set(true))
        .exceptionally(throwable -> {
          thrown.set(throwable);
          return null;
        });

    assertThat(accepted.get()).named("accepted").isTrue();
    assertThat(thrown.get()).named("thrown").isNull();
  }

  @Test
  public void testRetrier() throws Exception {
    CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
      Uninterruptibles.sleepUninterruptibly(1, TimeUnit.SECONDS);
      System.err.println("About to throw");
      throw new RuntimeException("wee");
    });

    future.thenComposeAsync(s -> CompletableFuture.completedFuture("a"));
    future = future.handleAsync((string, throwable) -> {
      System.err.println("Handling: " + string + "/" + throwable);
      if (string != null) {
        return CompletableFuture.completedFuture(string);
      }
      return CompletableFuture.supplyAsync(() -> {
        System.err.println("Sleeping");
        Uninterruptibles.sleepUninterruptibly(1, TimeUnit.SECONDS);
        System.err.println("Returning");
        return "done";
      });
    }).thenCompose(x -> x);

    assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo("done");
  }

}
