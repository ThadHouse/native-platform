/*
 * Copyright 2012 Adam Murdoch
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package net.rubygrapefruit.platform;

import net.rubygrapefruit.platform.file.FileInfo;

/**
 * Provides some information about a file on a Windows file system. This is a snapshot and does not change.
 *
 * <p>A snapshot be fetched using {@link WindowsFiles#stat(java.io.File)}</p>
 */
@ThreadSafe
public interface WindowsFileInfo extends FileInfo {
}
