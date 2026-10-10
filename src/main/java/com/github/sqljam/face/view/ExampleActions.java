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

import java.util.function.Consumer;

import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.service.ExampleDatabase;
import javafx.stage.Window;

/**
 * @Description: ExampleActions restores the example database from the login page and the main window, e.g. after the
 *               user removed its data source or its file. An existing example is replaced after a confirmation.
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public final class ExampleActions {

    private ExampleActions() {
    }

    /**
     * Restores the example database in the background
     *
     * @param onRestored called with the data source of the example when it is restored
     */
    public static void restore(Window owner, AppContext context, Consumer<ConnectionProfile> onRestored) {
        boolean exists = context.getProfileRegistry().findProfile(ExampleDatabase.PROFILE_ID).isPresent();
        if (exists && !Dialogs.confirm(owner, Messages.get("example.restore.title"),
                Messages.format("example.restore.message", ExampleDatabase.PROFILE_NAME))) {
            return;
        }
        TaskRunner.run(context::restoreExample, onRestored, e -> Dialogs.showError(owner,
                Messages.get("example.restore.error"), e));
    }
}
