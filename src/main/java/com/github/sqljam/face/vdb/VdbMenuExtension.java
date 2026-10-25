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

import com.github.sqljam.config.Config;
import com.github.sqljam.face.view.AppContext;
import com.github.sqljam.face.view.MenuExtension;
import com.github.sqljam.face.view.Messages;
import javafx.stage.Window;

/**
 * @Description: VdbMenuExtension is the entry of the vdb line in the File menu, switched by sqljam.vdb.enabled. It
 *               loads no class of the vdb line until its item is chosen.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public class VdbMenuExtension implements MenuExtension {

    public static final String ENABLED_KEY = "sqljam.vdb.enabled";

    @Override
    public String getId() {
        return "vdbImportMenuItem";
    }

    @Override
    public String getText() {
        return Messages.get("action.importVectorDatabase");
    }

    @Override
    public boolean isEnabled(Config config) {
        return config.getBoolean(ENABLED_KEY, true);
    }

    @Override
    public void show(Window owner, AppContext context) {
        new VdbImportDialog(owner, context, VdbProfileStore.getInstance()).showAndImport();
    }
}
