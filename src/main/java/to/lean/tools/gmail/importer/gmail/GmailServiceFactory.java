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

/** Creates a new {@code GmailService} for the given {@code User}. */
interface GmailServiceFactory {
  /*
   * NOTE(flan): This interface is in its own file because it can't be part of
   * the GmailService interface and remain package-private. This is because all
   * members of an interface are public even if the interface itself is package-
   * private. This causes issues when Guice tries to generate the factory since
   * the java.lang.reflect.Proxy doesn't support this. See:
   * https://github.com/google/guice/blob/87cd5ad7a3590d9288a9366e333b6878a54b2d14/extensions/assistedinject/src/com/google/inject/assistedinject/FactoryProvider2.java#L434
   */
  GmailService create(User user);
}
