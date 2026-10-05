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
package com.github.sqljam.face.model;

import com.github.sqljam.impexp.Exporter;
import lombok.Getter;
import lombok.Setter;

/**
 * @Description: AppSettings are user preferences of the application
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
@Getter
@Setter
public class AppSettings {

    public static final String DEFAULT_THEME = "Primer Dark";

    private String theme = DEFAULT_THEME;
    private String lastExportDirectory;
    private String lastImportDirectory;
    private int dataPageSize = 200;
    private int transferPageSize = Exporter.DEFAULT_PAGE_SIZE;
    private int lobPageSize = Exporter.DEFAULT_LOB_PAGE_SIZE;
    private long maxDataFileSize = 10L * 1024 * 1024;
    private double windowWidth = 1280;
    private double windowHeight = 800;
}
