(function (root) {
  "use strict";

  function escapeHtml(value) {
    return String(value == null ? "" : value).replace(/[&<>"']/g, function (char) {
      return {"&":"&amp;", "<":"&lt;", ">":"&gt;", "\"":"&quot;", "'":"&#39;"}[char];
    });
  }

  function renderInline(value) {
    return escapeHtml(value)
      .replace(/`([^`]+)`/g, "<code>$1</code>")
      .replace(/\*\*(.+?)\*\*/g, "<strong>$1</strong>")
      .replace(
        /\{\{detail:(RUN_[A-Za-z0-9_]+):(denominator|numerator|unmatched)\}\}/g,
        function (_, runId, group) {
          var labels = {
            denominator: "查看统计范围",
            numerator: "查看达到要求",
            unmatched: "查看未达到要求"
          };
          return '<button type="button" class="indicator-detail-trigger" data-run-id="' +
            runId + '" data-detail-group="' + group + '" aria-label="' + labels[group] +
            '">查看详情</button>';
        }
      );
  }

  function tableCells(line) {
    var placeholder = "\uE000";
    var protectedLine = line.replace(/\\\|/g, placeholder).trim();
    if (protectedLine.charAt(0) === "|") protectedLine = protectedLine.slice(1);
    if (protectedLine.charAt(protectedLine.length - 1) === "|") protectedLine = protectedLine.slice(0, -1);
    return protectedLine.split("|").map(function (cell) {
      return cell.trim().replace(new RegExp(placeholder, "g"), "|");
    });
  }

  function isTableSeparator(line) {
    var cells = tableCells(line);
    return cells.length > 0 && cells.every(function (cell) {
      return /^:?-{3,}:?$/.test(cell);
    });
  }

  function isTableStart(lines, index) {
    return index + 1 < lines.length
      && lines[index].trim().startsWith("|")
      && lines[index].trim().endsWith("|")
      && isTableSeparator(lines[index + 1]);
  }

  function renderTable(lines, startIndex) {
    var headers = tableCells(lines[startIndex]);
    var index = startIndex + 2;
    var rows = [];
    while (index < lines.length) {
      var line = lines[index].trim();
      if (!line.startsWith("|") || !line.endsWith("|")) break;
      rows.push(tableCells(line));
      index += 1;
    }
    var html = '<div class="message-table-wrap"><table class="message-table"><thead><tr>';
    headers.forEach(function (cell) { html += "<th>" + renderInline(cell) + "</th>"; });
    html += "</tr></thead><tbody>";
    rows.forEach(function (row) {
      html += "<tr>";
      for (var column = 0; column < headers.length; column += 1) {
        html += "<td>" + renderInline(row[column] || "") + "</td>";
      }
      html += "</tr>";
    });
    html += "</tbody></table></div>";
    return {html: html, nextIndex: index};
  }

  function renderSqlTabs(lines, startIndex) {
    var tabs = [];
    var current = null;
    var index = startIndex + 1;
    while (index < lines.length && lines[index].trim() !== ":::endsqltabs") {
      var marker = lines[index].match(/^@@tab\s+(.+)\s*$/);
      if (marker) {
        if (current) tabs.push(current);
        current = {label: marker[1], lines: []};
      } else if (current) {
        current.lines.push(lines[index]);
      }
      index += 1;
    }
    if (current) tabs.push(current);
    if (index < lines.length) index += 1;

    var html = '<div class="message-sql-tabs" data-sql-tabs>';
    html += '<div class="message-sql-tab-list" role="tablist" aria-label="SQL 版本">';
    tabs.forEach(function (tab, tabIndex) {
      html += '<button type="button" class="message-sql-tab-button' +
        (tabIndex === 0 ? " is-active" : "") + '" role="tab" data-sql-tab-index="' +
        tabIndex + '" aria-selected="' + (tabIndex === 0 ? "true" : "false") +
        '" tabindex="' + (tabIndex === 0 ? "0" : "-1") + '">' +
        renderInline(tab.label) + '</button>';
    });
    html += '</div>';
    tabs.forEach(function (tab, tabIndex) {
      html += '<div class="message-sql-tab-panel" role="tabpanel" data-sql-tab-panel="' +
        tabIndex + '"' + (tabIndex === 0 ? "" : " hidden") + '>' +
        renderAssistantMarkdown(tab.lines.join("\n")) + '</div>';
    });
    html += '</div>';
    return {html: html, nextIndex: index};
  }

  function renderAssistantMarkdown(text) {
    var lines = String(text == null ? "" : text).replace(/\r\n?/g, "\n").split("\n");
    var output = [];
    var index = 0;
    while (index < lines.length) {
      var line = lines[index];
      if (line.trim() === ":::sqltabs") {
        var sqlTabs = renderSqlTabs(lines, index);
        output.push(sqlTabs.html);
        index = sqlTabs.nextIndex;
        continue;
      }
      var details = line.match(/^:::details(?:\s+(.+))?\s*$/);
      if (details) {
        var label = details[1] || "查看技术详情";
        var detailLines = [];
        index += 1;
        while (index < lines.length && lines[index].trim() !== ":::") {
          detailLines.push(lines[index]);
          index += 1;
        }
        if (index < lines.length) index += 1;
        output.push(
          '<details class="message-details"><summary>' + renderInline(label) +
          '</summary><div class="message-details-body">' +
          renderAssistantMarkdown(detailLines.join("\n")) + "</div></details>"
        );
        continue;
      }
      var fence = line.match(/^```([A-Za-z0-9_-]*)\s*$/);
      if (fence) {
        var language = fence[1].toLowerCase();
        var codeLines = [];
        index += 1;
        while (index < lines.length && !/^```\s*$/.test(lines[index])) {
          codeLines.push(lines[index]);
          index += 1;
        }
        if (index < lines.length) index += 1;
        output.push(
          '<pre class="message-code"><code class="language-' + escapeHtml(language) + '">' +
          escapeHtml(codeLines.join("\n")) + "</code></pre>"
        );
        continue;
      }
      if (isTableStart(lines, index)) {
        var table = renderTable(lines, index);
        output.push(table.html);
        index = table.nextIndex;
        continue;
      }
      var heading = line.match(/^(#{1,3})\s+(.+)$/);
      if (heading) {
        var level = heading[1].length + 2;
        output.push("<h" + level + ">" + renderInline(heading[2]) + "</h" + level + ">");
        index += 1;
        continue;
      }
      if (!line.trim()) {
        index += 1;
        continue;
      }
      output.push("<p>" + renderInline(line) + "</p>");
      index += 1;
    }
    return output.join("");
  }

  root.renderAssistantMarkdown = renderAssistantMarkdown;
  if (root.document && !root.__messageSqlTabsBound) {
    root.__messageSqlTabsBound = true;
    root.document.addEventListener("click", function (event) {
      var button = event.target.closest && event.target.closest(".message-sql-tab-button");
      if (!button) return;
      var container = button.closest("[data-sql-tabs]");
      if (!container) return;
      var selectedIndex = button.getAttribute("data-sql-tab-index");
      container.querySelectorAll(".message-sql-tab-button").forEach(function (item) {
        var selected = item.getAttribute("data-sql-tab-index") === selectedIndex;
        item.classList.toggle("is-active", selected);
        item.setAttribute("aria-selected", selected ? "true" : "false");
        item.setAttribute("tabindex", selected ? "0" : "-1");
      });
      container.querySelectorAll(".message-sql-tab-panel").forEach(function (panel) {
        panel.hidden = panel.getAttribute("data-sql-tab-panel") !== selectedIndex;
      });
    });
  }
  if (typeof module !== "undefined" && module.exports) {
    module.exports = {renderAssistantMarkdown: renderAssistantMarkdown};
  }
}(typeof window !== "undefined" ? window : globalThis));
