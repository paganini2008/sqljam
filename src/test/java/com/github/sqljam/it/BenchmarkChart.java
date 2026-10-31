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
package com.github.sqljam.it;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import com.github.sqljam.face.view.DocImages;
import com.github.sqljam.face.view.FxTestSupport;
import javafx.scene.Group;
import javafx.scene.image.WritableImage;

/**
 * @Description: BenchmarkChart draws the chart of the Performance section again from a table of results which
 *               TransferBenchmark wrote (transfer.md), without measuring again, e.g. when a run on a slow network
 *               replaced it. The seconds follow from the rows a second, so that the chart tells the same numbers as
 *               the table. It is not a test of the build, run it by
 *               mvn test -Dtest=BenchmarkChart -Dbench.table=target/bench/transfer.md -Dbench.output=docs/assets
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
@ExtendWith(ApplicationExtension.class)
class BenchmarkChart {

    /**
     * Results of the rows of a table of TransferBenchmark: | Scenario | Rows | Time | Rows/s |
     */
    static List<TransferBenchmark.Result> parse(List<String> lines) {
        List<TransferBenchmark.Result> results = new ArrayList<>();
        for (String line : lines) {
            String[] cells = line.split("\\|");
            if (cells.length < 5 || !cells[4].trim().endsWith("k")) {
                continue;
            }
            String label = cells[1].trim();
            long rows = Long.parseLong(cells[2].trim().replace(",", ""));
            double rate = Double.parseDouble(cells[4].trim().replace("k", "")) * 1000;
            String color = label.startsWith("Qdrant") ? TransferBenchmark.VECTOR : label.startsWith("SQL package")
                    ? TransferBenchmark.IMPORT : label.endsWith("package") ? TransferBenchmark.PACKAGE
                    : TransferBenchmark.DIRECT;
            results.add(new TransferBenchmark.Result(label, color, rows, rows / rate));
        }
        return results;
    }

    @Test
    void redraw() throws Exception {
        List<String> lines = Files.readAllLines(new File(System.getProperty("bench.table",
                "target/bench/transfer.md")).toPath(), StandardCharsets.UTF_8);
        List<TransferBenchmark.Result> results = parse(lines);
        assertFalse(results.isEmpty());
        String environment = lines.stream().filter(line -> line.startsWith("Environment: ")).map(line -> line
                .substring("Environment: ".length())).findFirst().orElse("");
        String title = String.format(Locale.ENGLISH, "Throughput · %,d rows, 6 columns", results.get(0).rows);
        List<TransferBenchmark.Shape> shapes = TransferBenchmark.chart(title, environment, results.stream().filter(
                result -> !TransferBenchmark.VECTOR.equals(result.color)).collect(Collectors.toList()));
        File output = new File(System.getProperty("bench.output", "target/bench"));
        output.mkdirs();
        Files.writeString(new File(output, "benchmark.svg").toPath(), TransferBenchmark.svg(shapes),
                StandardCharsets.UTF_8);
        WritableImage image = FxTestSupport.call(() -> new Group(shapes.stream().map(TransferBenchmark.Shape::toNode)
                .collect(Collectors.toList())).snapshot(null, null));
        DocImages.write(image, new File(output, "benchmark.png"));
    }
}
