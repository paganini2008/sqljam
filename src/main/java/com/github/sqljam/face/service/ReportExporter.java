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
package com.github.sqljam.face.service;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.face.model.TransferReport;
import com.github.sqljam.face.model.TransferReport.TableReport;

/**
 * @Description: ReportExporter writes a report as Markdown, as one HTML file which opens anywhere, or as JSON: the
 *               summary, the options, the tables with their rows and verdicts, the objects, where it stopped and the
 *               skipped rows
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
public final class ReportExporter {

    public enum Format {
        MARKDOWN("md"), HTML("html"), JSON("json");

        private final String extension;

        Format(String extension) {
            this.extension = extension;
        }

        public String getExtension() {
            return extension;
        }
    }

    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId
            .systemDefault());

    private ReportExporter() {
    }

    public static String export(TransferReport report, Format format) throws IOException {
        switch (format) {
            case JSON:
                return ReportStore.MAPPER.writeValueAsString(report);
            case HTML:
                return html(report);
            default:
                return markdown(report);
        }
    }

    public static String time(long millis) {
        return millis > 0 ? TIME.format(Instant.ofEpochMilli(millis)) : "";
    }

    public static String duration(long millis) {
        long seconds = millis / 1000;
        return seconds >= 3600 ? String.format("%d h %d min", seconds / 3600, seconds % 3600 / 60) : seconds >= 60
                ? String.format("%d min %d s", seconds / 60, seconds % 60) : String.format(Locale.ENGLISH, "%.1f s",
                millis / 1000.0);
    }

    /**
     * Words of a status, e.g. Completed with errors
     */
    public static String words(Enum<?> value) {
        String text = value.name().replace('_', ' ').toLowerCase(Locale.ENGLISH);
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /**
     * Verdict of the rows of a table in the target
     */
    public static String verdict(TableReport table) {
        if (table.getTargetRows() == null) {
            return "";
        }
        Boolean verified = table.getVerified();
        String rows = String.format("%,d", table.getTargetRows());
        return verified == null ? rows : verified ? rows + " ✓" : rows + " ✗";
    }

    /**
     * Rows of the summary: name and value
     */
    public static List<String[]> summary(TransferReport report) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"Status", words(report.getStatus())});
        rows.add(new String[]{"Kind", words(report.getKind())});
        rows.add(new String[]{"Source", StringUtils.defaultString(report.getSource())});
        rows.add(new String[]{"Target", StringUtils.defaultString(report.getTarget())});
        rows.add(new String[]{"Started", time(report.getStartedAt())});
        rows.add(new String[]{"Ended", time(report.getEndedAt())});
        rows.add(new String[]{"Duration", duration(report.getElapsedMillis())});
        rows.add(new String[]{"Transaction", words(report.getTransaction())});
        rows.add(new String[]{"Tables", String.valueOf(report.getTables().size())});
        rows.add(new String[]{"Rows written", String.format("%,d", report.getWrittenRows())});
        rows.add(new String[]{"Rows skipped", String.format("%,d", report.getSkippedRowCount())});
        if (report.isRolledBack()) {
            rows.add(new String[]{"Kept in target", "Nothing, the transaction was rolled back"});
        }
        if (StringUtils.isNotBlank(report.getStoppedAt())) {
            rows.add(new String[]{"Stopped at", report.getStoppedAt()});
        }
        if (StringUtils.isNotBlank(report.getFailure())) {
            rows.add(new String[]{"Error", report.getFailure()});
        }
        if (StringUtils.isNotBlank(report.getSummary())) {
            rows.add(new String[]{"Summary", report.getSummary()});
        }
        return rows;
    }

    public static String[] tableRow(TableReport table) {
        return new String[]{table.getQualifiedName(), words(table.getStatus()), table.getSourceRows() >= 0 ? String
                .format("%,d", table.getSourceRows()) : "", String.format("%,d", table.getWritten()), String.format(
                "%,d", table.getSkipped()), verdict(table), duration(table.getElapsedMillis()), table.getRowsPerSecond()
                > 0 ? String.format(Locale.ENGLISH, "%,.0f", table.getRowsPerSecond()) : ""};
    }

    public static final String[] TABLE_HEADERS = {"Table", "Status", "Source rows", "Written", "Skipped", "Target rows",
            "Time", "Rows/s"};

    private static String cell(String text) {
        return StringUtils.defaultString(text).replace("|", "\\|").replaceAll("\\R", " ");
    }

    private static void markdownTable(StringBuilder text, String[] headers, List<String[]> rows) {
        text.append("| ").append(String.join(" | ", headers)).append(" |\n|");
        for (int i = 0; i < headers.length; i++) {
            text.append("---|");
        }
        text.append('\n');
        for (String[] row : rows) {
            text.append("| ");
            for (int i = 0; i < row.length; i++) {
                text.append(cell(row[i])).append(i < row.length - 1 ? " | " : " |\n");
            }
        }
    }

    static String markdown(TransferReport report) {
        StringBuilder text = new StringBuilder("# ").append(StringUtils.defaultIfBlank(report.getTitle(),
                "Transfer report")).append("\n\n");
        markdownTable(text, new String[]{"Item", "Value"}, summary(report));
        if (!report.getOptions().isEmpty()) {
            text.append("\n## Options\n\n");
            List<String[]> options = new ArrayList<>();
            report.getOptions().forEach((name, value) -> options.add(new String[]{name, value}));
            markdownTable(text, new String[]{"Option", "Value"}, options);
        }
        text.append("\n## Tables\n\n");
        List<String[]> tables = new ArrayList<>();
        report.getTables().forEach(table -> tables.add(tableRow(table)));
        markdownTable(text, TABLE_HEADERS, tables);
        if (!report.getObjects().isEmpty() || report.getFailedStatements() > 0) {
            text.append("\n## Objects\n\n");
            List<String[]> objects = new ArrayList<>();
            report.getObjects().forEach((kind, count) -> objects.add(new String[]{kind, String.valueOf(count)}));
            if (report.getFailedStatements() > 0) {
                objects.add(new String[]{"Failed statements", String.valueOf(report.getFailedStatements())});
            }
            markdownTable(text, new String[]{"Kind", "Count"}, objects);
        }
        if (!report.getSkippedRows().isEmpty()) {
            text.append("\n## Skipped rows\n\n");
            List<String[]> rows = new ArrayList<>();
            report.getSkippedRows().forEach(row -> rows.add(new String[]{row.getTable(), row.getKey(),
                    row.getError()}));
            markdownTable(text, new String[]{"Table", "Row", "Error"}, rows);
            if (report.getSkippedRowCount() > report.getSkippedRows().size()) {
                text.append(String.format("%n%,d more rows were skipped.%n", report.getSkippedRowCount() - report
                        .getSkippedRows().size()));
            }
        }
        if (StringUtils.isNotBlank(report.getFailureTrace())) {
            text.append("\n## Error\n\n```\n").append(report.getFailureTrace().trim()).append("\n```\n");
        }
        return text.toString();
    }

    static String escape(String text) {
        return StringUtils.defaultString(text).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static void htmlTable(StringBuilder html, String[] headers, List<String[]> rows) {
        html.append("<table><thead><tr>");
        for (String header : headers) {
            html.append("<th>").append(escape(header)).append("</th>");
        }
        html.append("</tr></thead><tbody>");
        for (String[] row : rows) {
            html.append("<tr>");
            for (String value : row) {
                html.append("<td>").append(escape(value)).append("</td>");
            }
            html.append("</tr>");
        }
        html.append("</tbody></table>");
    }

    static String html(TransferReport report) {
        String title = StringUtils.defaultIfBlank(report.getTitle(), "Transfer report");
        StringBuilder html = new StringBuilder("<!doctype html><html><head><meta charset=\"utf-8\"><meta name="
                + "\"viewport\" content=\"width=device-width, initial-scale=1\"><title>").append(escape(title))
                .append("</title><style>:root{--bg:#ffffff;--fg:#1f2328;--muted:#424a53;--line:#d0d7de;--ok:#1a7f37;"
                        + "--bad:#cf222e}@media (prefers-color-scheme: dark){:root{--bg:#0d1117;--fg:#ffffff;--muted:"
                        + "#f0f6fc;--line:#30363d;--ok:#3fb950;--bad:#f85149}}body{background:var(--bg);color:var(--fg);"
                        + "font:14px/1.5 -apple-system,Segoe UI,Helvetica,Arial,sans-serif;margin:24px auto;max-width:"
                        + "1100px;padding:0 16px}table{border-collapse:collapse;width:100%;margin:8px 0 24px}th,td{"
                        + "border:1px solid var(--line);padding:6px 10px;text-align:left;vertical-align:top}th{color:"
                        + "var(--muted)}pre{white-space:pre-wrap;border:1px solid var(--line);padding:12px;overflow:"
                        + "auto}.status{font-weight:700}.ok{color:var(--ok)}.bad{color:var(--bad)}</style></head><body>")
                .append("<h1>").append(escape(title)).append("</h1>");
        html.append("<p class=\"status ").append(report.getStatus().isKept() ? "ok" : "bad").append("\">")
                .append(escape(words(report.getStatus()))).append("</p>");
        htmlTable(html, new String[]{"Item", "Value"}, summary(report));
        if (!report.getOptions().isEmpty()) {
            html.append("<h2>Options</h2>");
            List<String[]> options = new ArrayList<>();
            for (Map.Entry<String, String> option : report.getOptions().entrySet()) {
                options.add(new String[]{option.getKey(), option.getValue()});
            }
            htmlTable(html, new String[]{"Option", "Value"}, options);
        }
        html.append("<h2>Tables</h2>");
        List<String[]> tables = new ArrayList<>();
        report.getTables().forEach(table -> tables.add(tableRow(table)));
        htmlTable(html, TABLE_HEADERS, tables);
        if (!report.getObjects().isEmpty() || report.getFailedStatements() > 0) {
            html.append("<h2>Objects</h2>");
            List<String[]> objects = new ArrayList<>();
            report.getObjects().forEach((kind, count) -> objects.add(new String[]{kind, String.valueOf(count)}));
            if (report.getFailedStatements() > 0) {
                objects.add(new String[]{"Failed statements", String.valueOf(report.getFailedStatements())});
            }
            htmlTable(html, new String[]{"Kind", "Count"}, objects);
        }
        if (!report.getSkippedRows().isEmpty()) {
            html.append("<h2>Skipped rows</h2>");
            List<String[]> rows = new ArrayList<>();
            report.getSkippedRows().forEach(row -> rows.add(new String[]{row.getTable(), row.getKey(),
                    row.getError()}));
            htmlTable(html, new String[]{"Table", "Row", "Error"}, rows);
        }
        if (StringUtils.isNotBlank(report.getFailureTrace())) {
            html.append("<h2>Error</h2><pre>").append(escape(report.getFailureTrace().trim())).append("</pre>");
        }
        return html.append("</body></html>\n").toString();
    }
}
