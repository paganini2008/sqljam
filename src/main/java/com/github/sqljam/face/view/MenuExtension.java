/*
 * Copyright 2023-2026 Fred Feng
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
package com.github.sqljam.face.view;

import com.github.sqljam.config.Config;
import javafx.stage.Window;

/**
 * @Description: MenuExtension adds an item to the File menu, found by ServiceLoader. An extension is a thin entry of
 *               an optional line (vector databases of the vdb line): its own settings switch it on or off, and the
 *               classes of the line are loaded only when its item is chosen, so that the main line runs without it.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public interface MenuExtension {

    /**
     * Id of the menu item, e.g. vdbImportMenuItem
     */
    String getId();

    /**
     * Text of the menu item
     */
    String getText();

    /**
     * Whether the item is shown, e.g. sqljam.vdb.enabled
     */
    boolean isEnabled(Config config);

    void show(Window owner, AppContext context);
}
