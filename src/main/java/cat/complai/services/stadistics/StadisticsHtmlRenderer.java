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
 * inline SVG charts (bar chart for monthly comparison, donut for interaction
 * distribution).  No external requests are made — all charts are pure SVG
 * embedded directly in the HTML, making them reliable in all email clients.
 *
 * <p>Layout: header → summary KPIs → two-column charts section → file lists
 * (complaints + feedback with download links).
 */
@Singleton
public class StadisticsHtmlRenderer {

    private static final String ACCENT_BLUE   = "#2563EB";
    private static final String ACCENT_GREEN  = "#16A34A";
    private static final String ACCENT_RED    = "#DC2626";
    private static final String ACCENT_AMBER  = "#D97706";
    private static final String ACCENT_PURPLE = "#7C3AED";
    private static final String GREY_DARK     = "#1F2937";

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter
            .ofPattern("d MMM yyyy")
            .withZone(ZoneId.of("Europe/Madrid"));

    /**
     * Renders the full HTML email for the given statistics model.
     *
     * @param model              the statistics model (must not be null)
     * @param reportGeneratedAt when the report was generated (used in the header)
     * @return complete HTML string ready for SES email body
     */
    public String render(StadisticsModel model, Instant reportGeneratedAt) {
        String kpiRow    = buildKpiRow(model);
        String barChart  = buildMonthlyBarChart(model);
        String donutChart = buildInteractionDonut(model);
        YearMonth currentYm = YearMonth.now(ZoneId.of("Europe/Madrid"));
        int year = currentYm.getYear();

        return "<!DOCTYPE html>\n" +
                "<html lang=\"ca\">\n" +
                "<head>\n" +
                "  <meta charset=\"UTF-8\">\n" +
                "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "  <title>ComplAI — Monthly Statistics Report</title>\n" +
                "  <style>\n" +
                "    @media only screen and (max-width:520px) {\n" +
                "      .mobile-stack { display:block !important; width:100% !important; max-width:100% !important; }\n" +
                "      .mobile-full  { width:100% !important; max-width:100% !important; }\n" +
                "      .mobile-hide { display:none !important; }\n" +
                "    }\n" +
                "  </style>\n" +
                "</head>\n" +
                "<body style=\"margin:0;padding:0;background-color:#EFF1F5;font-family:Arial,Helvetica,sans-serif;\">\n" +
                "\n" +
                "  <!-- Outer wrapper: centered, max 600px on desktop, full width on mobile -->\n" +
                "  <table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"background-color:#EFF1F5;\">\n" +
                "    <tr>\n" +
                "      <td align=\"center\">\n" +
                "\n" +
                "        <table width=\"600\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"\n" +
                "               class=\"mobile-full\" style=\"max-width:600px;\">\n" +
                "\n" +
                "          <!-- Header -->\n" +
                "          <tr>\n" +
                "            <td style=\"padding:28px 24px 24px;background-color:" + ACCENT_BLUE + ";\">\n" +
                "              <table cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\">\n" +
                "                <tr>\n" +
                "                  <td>\n" +
                "                    <p style=\"margin:0;font-size:11px;font-weight:700;letter-spacing:1.5px;text-transform:uppercase;color:rgba(255,255,255,0.7);\">ComplAI &middot; El Prat de Llobregat</p>\n" +
                "                    <h1 style=\"margin:6px 0 0;font-size:22px;font-weight:700;color:#FFFFFF;line-height:1.2;\">Monthly Statistics Report</h1>\n" +
                "                    <p style=\"margin:8px 0 0;font-size:13px;color:rgba(255,255,255,0.85);\">Any " + year + " &mdash; any vs. mes anterior</p>\n" +
                "                  </td>\n" +
                "                  <td align=\"right\" valign=\"middle\" class=\"mobile-hide\">\n" +
                "                    <div style=\"background-color:rgba(255,255,255,0.15);border-radius:8px;padding:10px 14px;display:inline-block;\">\n" +
                "                      <p style=\"margin:0;font-size:11px;color:rgba(255,255,255,0.7);\">Generat</p>\n" +
                "                      <p style=\"margin:2px 0 0;font-size:13px;font-weight:600;color:#FFFFFF;\">" + DATE_FMT.format(reportGeneratedAt) + "</p>\n" +
                "                    </div>\n" +
                "                  </td>\n" +
                "                </tr>\n" +
                "              </table>\n" +
                "            </td>\n" +
                "          </tr>\n" +
                "\n" +
                "          <!-- KPI row -->\n" +
                "          <tr>\n" +
                "            <td style=\"padding:20px 24px 0;background-color:#FFFFFF;\">\n" +
                "              <table cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\">\n" +
                "                <tr>\n" +
                kpiRow + "\n" +
                "                </tr>\n" +
                "              </table>\n" +
                "            </td>\n" +
                "          </tr>\n" +
                "\n" +
                "          <!-- Charts section: two columns, stacked on mobile -->\n" +
                "          <tr>\n" +
                "            <td style=\"padding:20px 24px;background-color:#F9FAFB;\">\n" +
                "              <table cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\">\n" +
                "                <tr>\n" +
                "                  <td class=\"mobile-stack\" width=\"58%\" valign=\"top\" style=\"padding-right:12px;\">\n" +
                "                    <div style=\"background-color:#FFFFFF;border:1px solid #E5E7EB;border-radius:12px;padding:20px;\">\n" +
                "                      <p style=\"margin:0 0 16px;font-size:14px;font-weight:700;color:#1F2937;\">Monthly Comparison (" + year + ")</p>\n" +
                barChart + "\n" +
                "                    </div>\n" +
                "                  </td>\n" +
                "                  <td class=\"mobile-stack\" width=\"42%\" valign=\"top\" style=\"padding-left:12px;\">\n" +
                "                    <div style=\"background-color:#FFFFFF;border:1px solid #E5E7EB;border-radius:12px;padding:20px;\">\n" +
                "                      <p style=\"margin:0 0 4px;font-size:14px;font-weight:700;color:#1F2937;\">Interaction Mix</p>\n" +
                "                      <p style=\"margin:0 0 12px;font-size:12px;color:#6B7280;\">" + getCurrentMonthLabel(model) + "</p>\n" +
                donutChart + "\n" +
                "                    </div>\n" +
                "                  </td>\n" +
                "                </tr>\n" +
                "              </table>\n" +
                "            </td>\n" +
                "          </tr>\n" +
                "\n" +
                "          <!-- File section: two columns, stacked on mobile -->\n" +
                "          <tr>\n" +
                "            <td style=\"padding:20px 24px 0;background-color:#FFFFFF;\">\n" +
                "              <table cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\">\n" +
                "                <tr>\n" +
                "                  <td class=\"mobile-stack\" width=\"50%\" valign=\"top\" style=\"padding-right:8px;\">\n" +
                "                    <div style=\"background-color:#F9FAFB;border:1px solid #E5E7EB;border-radius:10px;padding:16px;\">\n" +
                "                      <p style=\"margin:0 0 10px;font-size:13px;font-weight:700;color:#1F2937;\">&#128203; Reclamacions generades</p>\n" +
                buildComplaintList(model) + "\n" +
                "                    </div>\n" +
                "                  </td>\n" +
                "                  <td class=\"mobile-stack\" width=\"50%\" valign=\"top\" style=\"padding-left:8px;\">\n" +
                "                    <div style=\"background-color:#F9FAFB;border:1px solid #E5E7EB;border-radius:10px;padding:16px;\">\n" +
                "                      <p style=\"margin:0 0 10px;font-size:13px;font-weight:700;color:#1F2937;\">&#128172; Valoracions rebudes</p>\n" +
                buildFeedbackList(model) + "\n" +
                "                    </div>\n" +
                "                  </td>\n" +
                "                </tr>\n" +
                "              </table>\n" +
                "            </td>\n" +
                "          </tr>\n" +
                "\n" +
                "          <!-- Footer -->\n" +
                "          <tr>\n" +
                "            <td style=\"padding:24px 24px;background-color:#F3F4F6;\">\n" +
                "              <p style=\"margin:0;font-size:11px;color:#9CA3AF;text-align:center;\">\n" +
                "                Informe automatitzat generat per ComplAI &middot; El Prat de Llobregat.<br>\n" +
                "                No respongueu a aquest correu.\n" +
                "              </p>\n" +
                "            </td>\n" +
                "          </tr>\n" +
                "\n" +
                "        </table>\n" +
                "\n" +
                "      </td>\n" +
                "    </tr>\n" +
                "  </table>\n" +
                "\n" +
                "</body>\n" +
                "</html>\n";
    }

    // ─── KPI cards ─────────────────────────────────────────────────────────────

    private String buildKpiRow(StadisticsModel m) {
        MonthlyData curr = m.getCurrentMonth();
        int ask    = curr != null ? curr.getAskInteractions() : 0;
        int redact = curr != null ? curr.getRedactInteractions() : 0;
        int fb     = curr != null ? curr.getFeedbackCount() : 0;
        int diff   = totalDiff(m);
        String arrow = diff >= 0 ? "&#9650;" : "&#9660;";
        String diffColor = diff >= 0 ? ACCENT_GREEN : ACCENT_RED;
        String sign  = diff >= 0 ? "+" : "";

        return kpiCard("Consultes AI", String.valueOf(ask), ACCENT_BLUE) +
               kpiCard("Reclamacions", String.valueOf(redact), ACCENT_PURPLE) +
               kpiCard("Valoracions", String.valueOf(fb), ACCENT_AMBER) +
               changeCell(diff, arrow, diffColor, sign);
    }

    private String kpiCard(String label, String value, String color) {
        return "<td width=\"22%\" style=\"padding:0 4px;\">" +
               "  <div style=\"background-color:" + color + ";border-radius:10px;padding:14px 16px;text-align:center;\">" +
               "    <p style=\"margin:0 0 6px;font-size:11px;font-weight:600;letter-spacing:0.5px;text-transform:uppercase;color:rgba(255,255,255,0.75);\">" + label + "</p>" +
               "    <p style=\"margin:0;font-size:28px;font-weight:700;color:#FFFFFF;line-height:1;\">" + value + "</p>" +
               "  </div>" +
               "</td>";
    }

    private String changeCell(int diff, String arrow, String color, String sign) {
        return "<td width=\"12%\" style=\"padding:0 4px;\">" +
               "  <div style=\"background-color:#F9FAFB;border:1px solid #E5E7EB;border-radius:10px;padding:14px 12px;text-align:center;\">" +
               "    <p style=\"margin:0 0 4px;font-size:11px;color:#6B7280;\">vs. mes anterior</p>" +
               "    <p style=\"margin:0;font-size:22px;font-weight:700;color:" + color + ";\">" + arrow + " " + sign + diff + "</p>" +
               "    <p style=\"margin:4px 0 0;font-size:11px;color:#9CA3AF;\">total interaccions</p>" +
               "  </div>" +
               "</td>";
    }

    private int totalDiff(StadisticsModel m) {
        MonthlyData curr = m.getCurrentMonth();
        MonthlyData prev = m.getPreviousMonth();
        if (curr == null || prev == null) return 0;
        return diff(curr.getAskInteractions(), prev.getAskInteractions()) +
               diff(curr.getRedactInteractions(), prev.getRedactInteractions()) +
               diff(curr.getFeedbackCount(), prev.getFeedbackCount());
    }

    private int diff(int curr, int prev) { return curr - prev; }

    private String formatNumber(int n) {
        if (n >= 1000) {
            return String.format("%.1fk", n / 1000.0).replace(",", ".").replace(".0k", "k");
        }
        return String.valueOf(n);
    }

    // ─── Bar chart (monthly comparison) ─────────────────────────────────────────

    private String buildMonthlyBarChart(StadisticsModel m) {
        ArrayList<MonthlyData> yearly = m.getYearlyData();
        if (yearly == null || yearly.isEmpty()) {
            return "<p style=\"margin:0;font-size:12px;color:#9CA3AF;\">No hi ha dades disponibles.</p>";
        }

        int numMonths = yearly.size();
        // Take last 6 months max for display
        int displayStart = Math.max(0, numMonths - 6);
        ArrayList<MonthlyData> displayMonths = new ArrayList<>();
        for (int i = displayStart; i < numMonths; i++) {
            displayMonths.add(yearly.get(i));
        }

        // SVG dimensions
        int svgW = 340, svgH = 155;
        int chartLeft = 35, chartRight = 335, chartTop = 10, chartBot = 110;
        int chartW = chartRight - chartLeft;
        int chartH = chartBot - chartTop;

        int numBars = displayMonths.size();
        int barW = Math.min(32, (chartW - 20) / (numBars * 2));
        int barGap = 4;

        StringBuilder bars = new StringBuilder();

        // Find max for scaling
        int maxVal = 0;
        for (MonthlyData md : displayMonths) {
            maxVal = Math.max(maxVal, md.getAskInteractions());
            maxVal = Math.max(maxVal, md.getRedactInteractions());
            maxVal = Math.max(maxVal, md.getFeedbackCount());
        }
        if (maxVal == 0) maxVal = 1;

        // X-axis baseline
        bars.append(String.format(
            "<line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"#E5E7EB\" stroke-width=\"1\"/>",
            chartLeft, chartBot, chartRight, chartBot));

        // Draw bars for each month
        for (int i = 0; i < numBars; i++) {
            MonthlyData md = displayMonths.get(i);
            int groupX = chartLeft + i * (barW * 3 + barGap * 2) + 10;

            // Ask bar (blue)
            double askH = ((double) md.getAskInteractions() / maxVal) * chartH;
            bars.append(String.format(
                "<rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%.0f\" fill=\"%s\" rx=\"3\"/>",
                groupX, (int) (chartBot - askH), barW, askH, ACCENT_BLUE));

            // Redact bar (purple)
            double redactH = ((double) md.getRedactInteractions() / maxVal) * chartH;
            bars.append(String.format(
                "<rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%.0f\" fill=\"%s\" rx=\"3\"/>",
                groupX + barW + barGap, (int) (chartBot - redactH), barW, redactH, ACCENT_PURPLE));

            // Feedback bar (amber)
            double fbH = ((double) md.getFeedbackCount() / maxVal) * chartH;
            bars.append(String.format(
                "<rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%.0f\" fill=\"%s\" rx=\"3\"/>",
                groupX + 2 * (barW + barGap), (int) (chartBot - fbH), barW, fbH, ACCENT_AMBER));

            // Month label
            String monthLabel = getShortMonthLabel(md.getMonthLabel());
            int labelX = groupX + (barW * 3 + barGap * 2) / 2;
            bars.append(String.format(
                "<text x=\"%d\" y=\"%d\" text-anchor=\"middle\" font-size=\"9\" fill=\"#6B7280\">%s</text>",
                labelX, chartBot + 12, monthLabel));
        }

        // Legend
        bars.append(String.format(
            "<rect x=\"%d\" y=\"%d\" width=\"10\" height=\"10\" fill=\"%s\" rx=\"2\"/>" +
            "<text x=\"%d\" y=\"%d\" font-size=\"9\" fill=\"%s\">Consultes</text>" +
            "<rect x=\"%d\" y=\"%d\" width=\"10\" height=\"10\" fill=\"%s\" rx=\"2\"/>" +
            "<text x=\"%d\" y=\"%d\" font-size=\"9\" fill=\"%s\">Reclam.</text>" +
            "<rect x=\"%d\" y=\"%d\" width=\"10\" height=\"10\" fill=\"%s\" rx=\"2\"/>" +
            "<text x=\"%d\" y=\"%d\" font-size=\"9\" fill=\"%s\">Valorac.</text>",
            chartLeft, chartTop - 4, ACCENT_BLUE,
            chartLeft + 14, chartTop + 4, GREY_DARK,
            chartLeft + 80, chartTop - 4,
            chartLeft + 94, chartTop + 4, GREY_DARK,
            chartLeft + 140, chartTop - 4,
            chartLeft + 154, chartTop + 4, GREY_DARK));

        return "<svg viewBox=\"0 0 " + svgW + " " + svgH + "\" width=\"100%\" "
             + "style=\"display:block;overflow:visible;\" xmlns=\"http://www.w3.org/2000/svg\">" +
               bars.toString() +
               "</svg>";
    }

    // ─── Donut chart (interaction mix) ───────────────────────────────────────────

    private String buildInteractionDonut(StadisticsModel m) {
        MonthlyData curr = m.getCurrentMonth();
        int ask    = curr != null ? curr.getAskInteractions() : 0;
        int redact = curr != null ? curr.getRedactInteractions() : 0;
        int fb     = curr != null ? curr.getFeedbackCount() : 0;
        int total  = ask + redact + fb;

        double r = 55, cx = 65, cy = 65;
        int[] vals   = {ask, redact, fb};
        String[] colors = {ACCENT_BLUE, ACCENT_PURPLE, ACCENT_AMBER};
        String[] names  = {"Consultes", "Reclam.", "Valoracions"};

        StringBuilder slices  = new StringBuilder();
        StringBuilder legends = new StringBuilder();
        double cursor = -Math.PI / 2;

        for (int i = 0; i < 3; i++) {
            double share = total > 0 ? (vals[i] / (double) total) : 0;
            double end   = cursor + 2 * Math.PI * share;

            if (share > 0.005) {
                boolean large = share > 0.5;
                slices.append(String.format(
                    "<path d=\"M %.1f %.1f A %.1f %.1f 0 %b 1 %.1f %.1f Z\" fill=\"%s\"/>",
                    cx + r * Math.cos(cursor), cy + r * Math.sin(cursor),
                    r, r, large,
                    cx + r * Math.cos(end),    cy + r * Math.sin(end),
                    colors[i]));
            }

            double pct = total > 0 ? vals[i] * 100.0 / total : 0;
            legends.append("<div style=\"display:flex;align-items:center;margin-bottom:6px;\">");
            legends.append("<div style=\"width:10px;height:10px;border-radius:2px;background-color:" + colors[i] + ";margin-right:7px;flex-shrink:0;\"></div>");
            legends.append("<span style=\"font-size:11px;color:#374151;flex:1;\">" + names[i] + "</span>");
            legends.append("<span style=\"font-size:11px;font-weight:700;color:#1F2937;margin-left:4px;\">" + vals[i] + "</span>");
            legends.append("<span style=\"font-size:10px;color:#9CA3AF;margin-left:2px;\">(" + Math.round(pct) + "%)</span>");
            legends.append("</div>");

            cursor = end;
        }

        return "<table cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\">" +
               "  <tr>" +
               "    <td width=\"120\" align=\"center\">" +
               "      <svg viewBox=\"0 0 130 130\" width=\"120\" height=\"120\" xmlns=\"http://www.w3.org/2000/svg\">" +
               slices.toString() +
               "        <circle cx=\"" + fmtDbl(cx) + "\" cy=\"" + fmtDbl(cy) + "\" r=\"35\" fill=\"#FFFFFF\"/>" +
               "        <text x=\"" + fmtDbl(cx) + "\" y=\"" + fmtDbl(cy - 5) + "\" text-anchor=\"middle\" dominant-baseline=\"middle\" font-size=\"16\" font-weight=\"700\" fill=\"#1F2937\">" + total + "</text>" +
               "        <text x=\"" + fmtDbl(cx) + "\" y=\"" + fmtDbl(cy + 11) + "\" text-anchor=\"middle\" font-size=\"9\" fill=\"#9CA3AF\">total</text>" +
               "      </svg>" +
               "    </td>" +
               "    <td valign=\"middle\" style=\"padding-left:4px;\">" +
               legends.toString() +
               "    </td>" +
               "  </tr>" +
               "</table>";
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
                url  = cf.getUrl() != null ? cf.getUrl().toString() : "";
            } else if (f instanceof FeedbackFile ff) {
                name = ff.getFileName();
                url  = ff.getUrl() != null ? ff.getUrl().toString() : "";
            }
            if (name == null) continue;

            boolean isLast = (i == files.size() - 1);
            sb.append("<div style=\"display:flex;align-items:baseline;margin-bottom:").append(isLast ? "0" : "8px").append(";\">");
            sb.append("<div style=\"width:6px;height:6px;border-radius:50%;background-color:").append(ACCENT_BLUE).append(";margin-right:8px;flex-shrink:0;margin-top:4px;\"></div>");
            sb.append("<span style=\"font-size:11px;color:#374151;flex:1;word-break:break-all;\">").append(escHtml(name)).append("</span>");
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

    // ─── Helpers ────────────────────────────────────────────────────────────────

    private String escHtml(String s) {
        if (s == null) return "";
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

    private String getShortMonthLabel(String fullLabel) {
        // Converts "Maig 2026" to "Mai" or just first 3 chars
        if (fullLabel == null || fullLabel.length() < 3) return fullLabel;
        return fullLabel.substring(0, 3);
    }
}