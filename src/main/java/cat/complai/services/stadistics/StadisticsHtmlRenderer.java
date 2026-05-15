package cat.complai.services.stadistics;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.YearMonth;
import java.util.ArrayList;
import cat.complai.services.stadistics.models.ComplaintFile;
import cat.complai.services.stadistics.models.FeedbackFile;
import cat.complai.services.stadistics.models.StadisticsModel;
import cat.complai.services.stadistics.models.StadisticsModel.MonthlyData;
import jakarta.inject.Singleton;

/**
 * Renders a {@link StadisticsModel} as a polished HTML email body with
 * inline SVG charts (donut for interaction distribution). No external requests
 * are made — all charts are pure SVG embedded directly in the HTML, making them
 * reliable in all email clients.
 *
 * <p>
 * Layout: header → summary KPIs → donut chart → monthly table → file lists
 * (complaints + feedback with download links).
 */
@Singleton
public class StadisticsHtmlRenderer {

  private static final String ACCENT_BLUE = "#2563EB";
  private static final String ACCENT_GREEN = "#16A34A";
  private static final String ACCENT_RED = "#DC2626";
  private static final String ACCENT_AMBER = "#D97706";
  private static final String ACCENT_PURPLE = "#7C3AED";
  private static final DateTimeFormatter DATE_FMT = DateTimeFormatter
      .ofPattern("d MMM yyyy")
      .withZone(ZoneId.of("Europe/Madrid"));

  private static final String CSS_STYLES = """
      @media only screen and (max-width:520px) {
        .mobile-stack { display:block !important; width:100% !important; max-width:100% !important; }
        .mobile-full  { width:100% !important; max-width:100% !important; }
        .mobile-hide { display:none !important; }
      }
      """;

  /**
   * Renders the full HTML email for the given statistics model.
   *
   * @param model             the statistics model (must not be null)
   * @param reportGeneratedAt when the report was generated (used in the header)
   * @param prediction        AI-generated prediction HTML (or fallback message)
   * @return complete HTML string ready for SES email body
   */
  public String render(StadisticsModel model, Instant reportGeneratedAt, String prediction) {
    int year = YearMonth.now(ZoneId.of("Europe/Madrid")).getYear();
    String currentMonthLabel = getCurrentMonthLabel(model);

    return """
        <!DOCTYPE html>
        <html lang="ca">
        <head>
          <meta charset="UTF-8">
          <meta name="viewport" content="width=device-width, initial-scale=1.0">
          <title>ComplAI — Monthly Statistics Report</title>
          <style>
            %s
          </style>
        </head>
        <body style="margin:0;padding:0;background-color:#EFF1F5;font-family:Arial,Helvetica,sans-serif;">
        %s
        </body>
        </html>
        """.formatted(CSS_STYLES, buildOuterWrapper(model, reportGeneratedAt, year, currentMonthLabel, prediction));
  }

  // ─── HTML structure builders ─────────────────────────────────────────────────

  private String buildOuterWrapper(StadisticsModel model, Instant reportGeneratedAt, int year, String currentMonthLabel,
      String prediction) {
    return """
        <table width="100%%" cellpadding="0" cellspacing="0" border="0" style="background-color:#EFF1F5;">
          <tr>
            <td align="center">
              <table width="600" cellpadding="0" cellspacing="0" border="0"
                     class="mobile-full" style="max-width:600px;">
                %s
                %s
                %s
                %s
                %s
                %s
                %s
              </table>
            </td>
          </tr>
        </table>
        """.formatted(
        buildHeader(model, reportGeneratedAt, year, currentMonthLabel),
        buildKpiRowSection(model),
        buildChartsSection(model, currentMonthLabel),
        buildPredictionSection(prediction),
        buildMonthlyBreakdownSection(model, year),
        buildFilesSection(model),
        buildFooter());
  }

  private String buildHeader(StadisticsModel model, Instant reportGeneratedAt, int year, String currentMonthLabel) {
    return """
        <tr>
          <td style="padding:28px 24px 24px;background-color:%s;">
            <table cellpadding="0" cellspacing="0" border="0" width="100%%">
              <tr>
                <td>
                  <p style="margin:0;font-size:11px;font-weight:700;letter-spacing:1.5px;text-transform:uppercase;color:rgba(255,255,255,0.7);">ComplAI &middot; El Prat de Llobregat</p>
                  <h1 style="margin:6px 0 0;font-size:22px;font-weight:700;color:#FFFFFF;line-height:1.2;">Monthly Statistics Report</h1>
                  <p style="margin:8px 0 0;font-size:13px;color:rgba(255,255,255,0.85);">Resum mensual any %d &mdash; acumulat gener a %s</p>
                </td>
                <td align="right" valign="middle" class="mobile-hide">
                  <div style="background-color:rgba(255,255,255,0.15);border-radius:8px;padding:10px 14px;display:inline-block;">
                    <p style="margin:0;font-size:11px;color:rgba(255,255,255,0.7);">Generat</p>
                    <p style="margin:2px 0 0;font-size:13px;font-weight:600;color:#FFFFFF;">%s</p>
                  </div>
                </td>
              </tr>
            </table>
          </td>
        </tr>
        """
        .formatted(ACCENT_BLUE, year, currentMonthLabel, DATE_FMT.format(reportGeneratedAt));
  }

  private String buildKpiRowSection(StadisticsModel model) {
    return """
        <tr>
          <td style="padding:20px 24px 0;background-color:#FFFFFF;">
            <table cellpadding="0" cellspacing="0" border="0" width="100%%">
              <tr>
                %s
              </tr>
            </table>
          </td>
        </tr>
        """.formatted(buildKpiRow(model));
  }

  private String buildChartsSection(StadisticsModel model, String currentMonthLabel) {
    return """
        <tr>
          <td style="padding:20px 24px;background-color:#F9FAFB;">
            <table cellpadding="0" cellspacing="0" border="0" width="100%%">
              <tr>
                <td class="mobile-stack" width="100%%" valign="top">
                  <div style="background-color:#FFFFFF;border:1px solid #E5E7EB;border-radius:12px;padding:20px;">
                    <p style="margin:0 0 4px;font-size:14px;font-weight:700;color:#1F2937;">Interaction Mix</p>
                    <p style="margin:0 0 12px;font-size:12px;color:#6B7280;">%s</p>
                    %s
                  </div>
                </td>
              </tr>
            </table>
          </td>
        </tr>
        """.formatted(currentMonthLabel, buildInteractionDonut(model));
  }

  private String buildPredictionSection(String prediction) {
    if (prediction == null || prediction.isBlank()) {
      return "";
    }
    return """
        <tr>
          <td style="padding:20px 24px;background-color:#F9FAFB;">
            <div style="background-color:#FFFFFF;border:1px solid #E5E7EB;border-radius:12px;padding:20px;">
              <p style="margin:0 0 12px;font-size:14px;font-weight:700;color:#1F2937;">Predicció i Anàlisi</p>
              %s
            </div>
          </td>
        </tr>
        """.formatted(prediction);
  }

  private String buildMonthlyBreakdownSection(StadisticsModel model, int year) {
    return """
        <tr>
          <td style="padding:20px 24px;background-color:#FFFFFF;">
            <div style="border:1px solid #E5E7EB;border-radius:12px;padding:20px;">
              <p style="margin:0 0 16px;font-size:14px;font-weight:700;color:#1F2937;">Desglossament mensual (%d)</p>
              %s
            </div>
          </td>
        </tr>
        """.formatted(year, buildMonthlyTable(model));
  }

  private String buildFilesSection(StadisticsModel model) {
    return """
        <tr>
          <td style="padding:0 24px 20px;background-color:#FFFFFF;">
            <table cellpadding="0" cellspacing="0" border="0" width="100%%">
              <tr>
                <td class="mobile-stack" width="50%%" valign="top" style="padding-right:8px;">
                  <div style="background-color:#F9FAFB;border:1px solid #E5E7EB;border-radius:10px;padding:16px;">
                    <p style="margin:0 0 10px;font-size:13px;font-weight:700;color:#1F2937;">&#128203; Reclamacions generades</p>
                    %s
                  </div>
                </td>
                <td class="mobile-stack" width="50%%" valign="top" style="padding-left:8px;">
                  <div style="background-color:#F9FAFB;border:1px solid #E5E7EB;border-radius:10px;padding:16px;">
                    <p style="margin:0 0 10px;font-size:13px;font-weight:700;color:#1F2937;">&#128172; Valoracions rebudes</p>
                    %s
                  </div>
                </td>
              </tr>
            </table>
          </td>
        </tr>
        """
        .formatted(buildComplaintList(model), buildFeedbackList(model));
  }

  private String buildFooter() {
    return """
        <tr>
          <td style="padding:24px 24px;background-color:#F3F4F6;">
            <p style="margin:0;font-size:11px;color:#9CA3AF;text-align:center;">
              Informe automatitzat generat per ComplAI &middot; El Prat de Llobregat.<br>
              No respongueu a aquest correu.
            </p>
          </td>
        </tr>
        """;
  }

  // ─── KPI cards ─────────────────────────────────────────────────────────────

  private String buildKpiRow(StadisticsModel m) {
    ArrayList<MonthlyData> yearly = m.getYearlyData();
    int ask = 0, redact = 0, fb = 0;
    if (yearly != null) {
      for (MonthlyData md : yearly) {
        ask += md.getAskInteractions();
        redact += md.getRedactInteractions();
        fb += md.getFeedbackCount();
      }
    }
    int diff = totalDiff(m);
    String arrow = diff >= 0 ? "&#9650;" : "&#9660;";
    String diffColor = diff >= 0 ? ACCENT_GREEN : ACCENT_RED;
    String sign = diff >= 0 ? "+" : "";

    return kpiCard("Consultes AI", String.valueOf(ask), ACCENT_BLUE) +
        kpiCard("Reclamacions", String.valueOf(redact), ACCENT_PURPLE) +
        kpiCard("Valoracions", String.valueOf(fb), ACCENT_AMBER) +
        changeCell(diff, arrow, diffColor, sign);
  }

  private String kpiCard(String label, String value, String color) {
    return """
        <td width="22%%" style="padding:0 4px;">
          <div style="background-color:%s;border-radius:10px;padding:14px 16px;text-align:center;">
            <p style="margin:0 0 6px;font-size:11px;font-weight:600;letter-spacing:0.5px;text-transform:uppercase;color:rgba(255,255,255,0.75);">%s</p>
            <p style="margin:0;font-size:28px;font-weight:700;color:#FFFFFF;line-height:1;">%s</p>
          </div>
        </td>
        """
        .formatted(color, label, value);
  }

  private String changeCell(int diff, String arrow, String color, String sign) {
    return """
        <td width="12%%" style="padding:0 4px;">
          <div style="background-color:#F9FAFB;border:1px solid #E5E7EB;border-radius:10px;padding:14px 12px;text-align:center;">
            <p style="margin:0 0 4px;font-size:11px;color:#6B7280;">vs. mes anterior</p>
            <p style="margin:0;font-size:22px;font-weight:700;color:%s;">%s %s%d</p>
            <p style="margin:4px 0 0;font-size:11px;color:#9CA3AF;">total interaccions</p>
          </div>
        </td>
        """
        .formatted(color, arrow, sign, diff);
  }

  private int totalDiff(StadisticsModel m) {
    MonthlyData curr = m.getCurrentMonth();
    MonthlyData prev = m.getPreviousMonth();
    if (curr == null || prev == null)
      return 0;
    return diff(curr.getAskInteractions(), prev.getAskInteractions()) +
        diff(curr.getRedactInteractions(), prev.getRedactInteractions()) +
        diff(curr.getFeedbackCount(), prev.getFeedbackCount());
  }

  private int diff(int curr, int prev) {
    return curr - prev;
  }

  // ─── Donut chart (interaction mix) ───────────────────────────────────────────

  private String buildInteractionDonut(StadisticsModel m) {
    MonthlyData curr = m.getCurrentMonth();
    int ask = curr != null ? curr.getAskInteractions() : 0;
    int redact = curr != null ? curr.getRedactInteractions() : 0;
    int fb = curr != null ? curr.getFeedbackCount() : 0;
    int total = ask + redact + fb;

    double r = 55, cx = 65, cy = 65;
    int[] vals = { ask, redact, fb };
    String[] colors = { ACCENT_BLUE, ACCENT_PURPLE, ACCENT_AMBER };
    String[] names = { "Consultes", "Reclam.", "Valoracions" };

    StringBuilder slices = new StringBuilder();
    StringBuilder legends = new StringBuilder();
    double cursor = -Math.PI / 2;

    for (int i = 0; i < 3; i++) {
      double share = total > 0 ? (vals[i] / (double) total) : 0;
      double end = cursor + 2 * Math.PI * share;

      if (share > 0.005) {
        boolean large = share > 0.5;
        slices.append(String.format(
            "<path d=\"M %.1f %.1f A %.1f %.1f 0 %b 1 %.1f %.1f Z\" fill=\"%s\"/>",
            cx + r * Math.cos(cursor), cy + r * Math.sin(cursor),
            r, r, large,
            cx + r * Math.cos(end), cy + r * Math.sin(end),
            colors[i]));
      }

      double pct = total > 0 ? vals[i] * 100.0 / total : 0;
      legends.append("<div style=\"display:flex;align-items:center;margin-bottom:6px;\">");
      legends.append("<div style=\"width:10px;height:10px;border-radius:2px;background-color:" + colors[i]
          + ";margin-right:7px;flex-shrink:0;\"></div>");
      legends.append("<span style=\"font-size:11px;color:#374151;flex:1;\">" + names[i] + "</span>");
      legends.append(
          "<span style=\"font-size:11px;font-weight:700;color:#1F2937;margin-left:4px;\">" + vals[i] + "</span>");
      legends.append("<span style=\"font-size:10px;color:#9CA3AF;margin-left:2px;\">(" + Math.round(pct) + "%)</span>");
      legends.append("</div>");

      cursor = end;
    }

    return """
        <table cellpadding="0" cellspacing="0" border="0" width="100%%">
          <tr>
            <td width="120" align="center">
              <svg viewBox="0 0 130 130" width="120" height="120" xmlns="http://www.w3.org/2000/svg">
                %s
                <circle cx="%s" cy="%s" r="35" fill="#FFFFFF"/>
                <text x="%s" y="%s" text-anchor="middle" dominant-baseline="middle" font-size="16" font-weight="700" fill="#1F2937">%d</text>
                <text x="%s" y="%s" text-anchor="middle" font-size="9" fill="#9CA3AF">total</text>
              </svg>
            </td>
            <td valign="middle" style="padding-left:4px;">
              %s
            </td>
          </tr>
        </table>
        """
        .formatted(
            slices.toString(),
            fmtDbl(cx), fmtDbl(cy),
            fmtDbl(cx), fmtDbl(cy - 5),
            total,
            fmtDbl(cx), fmtDbl(cy + 11),
            legends.toString());
  }

  // ─── File section ────────────────────────────────────────────────────────────

  private String buildComplaintList(StadisticsModel m) {
    ArrayList<ComplaintFile> complaints = null;
    MonthlyData curr = m.getCurrentMonth();
    if (curr != null) {
      complaints = curr.getComplaintFiles();
    }
    return buildFileList(complaints, "No hi ha reclamacions aquest mes.");
  }

  private String buildFeedbackList(StadisticsModel m) {
    ArrayList<FeedbackFile> feedbacks = null;
    MonthlyData curr = m.getCurrentMonth();
    if (curr != null) {
      feedbacks = curr.getFeedbackFiles();
    }
    return buildFileList(feedbacks, "No hi ha valoracions aquest mes.");
  }

  private String buildFileList(ArrayList<?> files, String emptyMsg) {
    if (files == null || files.isEmpty()) {
      return "<p style=\"margin:0;font-size:12px;color:#9CA3AF;font-style:italic;\">" + emptyMsg + "</p>";
    }

    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < files.size(); i++) {
      Object f = files.get(i);
      String name = null, url = null;
      if (f instanceof ComplaintFile cf) {
        name = cf.getFileName();
        url = cf.getUrl() != null ? cf.getUrl().toString() : "";
      } else if (f instanceof FeedbackFile ff) {
        name = ff.getFileName();
        url = ff.getUrl() != null ? ff.getUrl().toString() : "";
      }
      if (name == null)
        continue;

      boolean isLast = (i == files.size() - 1);
      sb.append("<div style=\"display:flex;align-items:baseline;margin-bottom:").append(isLast ? "0" : "8px")
          .append(";\">");
      sb.append("<div style=\"width:6px;height:6px;border-radius:50%;background-color:").append(ACCENT_BLUE)
          .append(";margin-right:8px;flex-shrink:0;margin-top:4px;\"></div>");
      sb.append("<span style=\"font-size:11px;color:#374151;flex:1;word-break:break-all;\">").append(escHtml(name))
          .append("</span>");
      if (url != null && !url.isBlank()) {
        sb.append("<a href=\"").append(escHtml(url))
            .append("\" style=\"font-size:11px;color:")
            .append(ACCENT_BLUE)
            .append(";font-weight:600;text-decoration:none;white-space:nowrap;margin-left:8px;\">Baixar &#8595;</a>");
      }
      sb.append("</div>");
    }
    return sb.toString();
  }

  // ─── Monthly breakdown table ────────────────────────────────────────────────

  private String buildMonthlyTable(StadisticsModel m) {
    ArrayList<MonthlyData> yearly = m.getYearlyData();
    if (yearly == null || yearly.isEmpty()) {
      return "";
    }

    StringBuilder sb = new StringBuilder();
    sb.append(
        "<table cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%%\" style=\"border-collapse:collapse;\">");

    // Header row
    sb.append("<tr style=\"background-color:#F9FAFB;\">");
    sb.append(
        "<th style=\"padding:10px 12px;font-size:11px;font-weight:700;color:#6B7280;text-transform:uppercase;letter-spacing:0.5px;text-align:left;border-bottom:2px solid #E5E7EB;\">Mes</th>");
    sb.append(
        "<th style=\"padding:10px 12px;font-size:11px;font-weight:700;color:#6B7280;text-transform:uppercase;letter-spacing:0.5px;text-align:center;border-bottom:2px solid #E5E7EB;\">Consultes</th>");
    sb.append(
        "<th style=\"padding:10px 12px;font-size:11px;font-weight:700;color:#6B7280;text-transform:uppercase;letter-spacing:0.5px;text-align:center;border-bottom:2px solid #E5E7EB;\">Reclam.</th>");
    sb.append(
        "<th style=\"padding:10px 12px;font-size:11px;font-weight:700;color:#6B7280;text-transform:uppercase;letter-spacing:0.5px;text-align:center;border-bottom:2px solid #E5E7EB;\">Valorac.</th>");
    sb.append("</tr>");

    // Data rows
    for (int i = 0; i < yearly.size(); i++) {
      MonthlyData md = yearly.get(i);
      String bgColor = (i % 2 == 0) ? "#FFFFFF" : "#F9FAFB";
      sb.append("<tr style=\"background-color:").append(bgColor).append(";\">");
      sb.append(
          "<td style=\"padding:8px 12px;font-size:13px;font-weight:600;color:#1F2937;border-bottom:1px solid #E5E7EB;\">")
          .append(escHtml(md.getMonthLabel())).append("</td>");
      sb.append(
          "<td style=\"padding:8px 12px;font-size:13px;color:#374151;text-align:center;border-bottom:1px solid #E5E7EB;\">")
          .append(md.getAskInteractions()).append("</td>");
      sb.append(
          "<td style=\"padding:8px 12px;font-size:13px;color:#374151;text-align:center;border-bottom:1px solid #E5E7EB;\">")
          .append(md.getRedactInteractions()).append("</td>");
      sb.append(
          "<td style=\"padding:8px 12px;font-size:13px;color:#374151;text-align:center;border-bottom:1px solid #E5E7EB;\">")
          .append(md.getFeedbackCount()).append("</td>");
      sb.append("</tr>");
    }

    // Totals row
    int totalAsk = 0, totalRedact = 0, totalFb = 0;
    for (MonthlyData md : yearly) {
      totalAsk += md.getAskInteractions();
      totalRedact += md.getRedactInteractions();
      totalFb += md.getFeedbackCount();
    }
    sb.append("<tr style=\"background-color:#EFF6FF;\">");
    sb.append(
        "<td style=\"padding:10px 12px;font-size:13px;font-weight:700;color:#1F2937;border-bottom:none;\">Total</td>");
    sb.append(
        "<td style=\"padding:10px 12px;font-size:13px;font-weight:700;color:#2563EB;text-align:center;border-bottom:none;\">")
        .append(totalAsk).append("</td>");
    sb.append(
        "<td style=\"padding:10px 12px;font-size:13px;font-weight:700;color:#7C3AED;text-align:center;border-bottom:none;\">")
        .append(totalRedact).append("</td>");
    sb.append(
        "<td style=\"padding:10px 12px;font-size:13px;font-weight:700;color:#D97706;text-align:center;border-bottom:none;\">")
        .append(totalFb).append("</td>");
    sb.append("</tr>");

    sb.append("</table>");
    return sb.toString();
  }

  // ─── Helpers ────────────────────────────────────────────────────────────────

  private String escHtml(String s) {
    if (s == null)
      return "";
    return s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }

  private String fmtDbl(double v) {
    return String.format("%.1f", v);
  }

  private String getCurrentMonthLabel(StadisticsModel m) {
    MonthlyData curr = m.getCurrentMonth();
    return curr != null ? curr.getMonthLabel() : "";
  }
}