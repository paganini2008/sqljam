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
package com.github.sqljam.face.vdb;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import com.github.sqljam.face.view.AppContext;
import javafx.scene.control.Dialog;
import javafx.stage.Window;

/**
 * @Description: VdbTestDialogs creates the dialogs of the vdb line for tests of other packages, e.g. the layout of
 *               every dialog on small screens
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public final class VdbTestDialogs {

    private VdbTestDialogs() {
    }

    public static Map<String, Supplier<Dialog<?>>> create(Window owner, AppContext context, File dir) {
        VdbProfileStore store = new VdbProfileStore(new File(dir, "vdb-layout.json"));
        Map<String, Supplier<Dialog<?>>> dialogs = new LinkedHashMap<>();
        dialogs.put("Import from a vector database", () -> new VdbImportDialog(owner, context, store));
        dialogs.put("Vector database", () -> new VdbConnectionDialog(owner, null, true));
        return dialogs;
    }
}
