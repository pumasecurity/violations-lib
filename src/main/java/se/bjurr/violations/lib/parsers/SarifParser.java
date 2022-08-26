package se.bjurr.violations.lib.parsers;

import static se.bjurr.violations.lib.model.Violation.violationBuilder;
import static se.bjurr.violations.lib.util.Utils.isNullOrEmpty;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import se.bjurr.violations.lib.ViolationsLogger;
import se.bjurr.violations.lib.model.SEVERITY;
import se.bjurr.violations.lib.model.Violation;
import se.bjurr.violations.lib.model.generated.sarif.Artifact;
import se.bjurr.violations.lib.model.generated.sarif.Location;
import se.bjurr.violations.lib.model.generated.sarif.Message;
import se.bjurr.violations.lib.model.generated.sarif.PhysicalLocation;
import se.bjurr.violations.lib.model.generated.sarif.Region;
import se.bjurr.violations.lib.model.generated.sarif.ReportingDescriptor;
import se.bjurr.violations.lib.model.generated.sarif.Result;
import se.bjurr.violations.lib.model.generated.sarif.Result.Level;
import se.bjurr.violations.lib.model.generated.sarif.Run;
import se.bjurr.violations.lib.model.generated.sarif.SarifSchema;
import se.bjurr.violations.lib.reports.Parser;
import se.bjurr.violations.lib.util.Utils;

public class SarifParser implements ViolationsParser {
  public static final String SARIF_RESULTS_CORRELATION_GUID = "correlationGuid";

  private static class ResultDeserializer implements JsonDeserializer<Level> {

    @Override
    public Level deserialize(
        final JsonElement json, final Type typeOfT, final JsonDeserializationContext context)
        throws JsonParseException {
      try {
        final String asString = json.getAsString();
        return Level.fromValue(asString);
      } catch (final Exception e) {
        return Level.NONE;
      }
    }
  }

  @Override
  public Set<Violation> parseReportOutput(
      final String reportContent, final ViolationsLogger violationsLogger) throws Exception {
    final SarifSchema report = new GsonBuilder()
        .registerTypeAdapter(Level.class, new ResultDeserializer())
        .create()
        .fromJson(reportContent, SarifSchema.class);

    final Set<Violation> violations = new TreeSet<>();
    if (report.getRuns() == null) {
      return violations;
    }
    for (final Run run : report.getRuns()) {
      String reporter = "Sarif";
      final boolean isToolSet = run.getTool() != null && run.getTool().getDriver() != null;
      if (isToolSet
          && run.getTool().getDriver().getName() != null
          && !run.getTool().getDriver().getName().trim().isEmpty()) {
        reporter = run.getTool().getDriver().getName();
      }
      final List<Artifact> artifacts = new ArrayList<>(run.getArtifacts());
      final Map<String, String> helpMap = this.extractHelpText(run);
      final Map<String, String> ruleDescriptionMap = this.extractRuleDescriptionText(run);

      for (final Result result : run.getResults()) {
        // Multiple instances of the same rule id / message / location are not added to
        // the violations collection. Parse unique identifier fields if they exist
        final Map<String, String> specifics = new HashMap<>();
        final String correlationGuid = result.getCorrelationGuid();
        if (!isNullOrEmpty(correlationGuid)) {
          specifics.put(SARIF_RESULTS_CORRELATION_GUID, correlationGuid);
        }

        final String ruleId = result.getRuleId();
        final String message = this.extractMessage(result.getMessage());
        if (isNullOrEmpty(message)) {
          continue;
        }
        final Level level = result.getLevel();

        final List<Location> locations = result.getLocations();
        final boolean hasLocations = locations != null && locations.size() > 0;
        if (hasLocations) {
          for (final Location location : locations) {
            final PhysicalLocation physicalLocation = location.getPhysicalLocation();
            final Region region = physicalLocation.getRegion();
            if (region == null) {
              continue;
            }
            final Integer startLine = region.getStartLine();
            if (startLine == null) {
              continue;
            }
            String filename = null;
            final Integer artifactLocationIndex = physicalLocation.getArtifactLocation().getIndex();
            if (artifactLocationIndex != null && artifactLocationIndex != -1) {
              filename = artifacts.get(artifactLocationIndex).getLocation().getUri();
            } else {
              filename = physicalLocation.getArtifactLocation().getUri();
            }
            final String regionMessage = this.extractMessage(region.getMessage());
            final StringBuilder fullMessage = new StringBuilder(message);
            if (regionMessage != null) {
              fullMessage.append("\n\n").append(regionMessage);
            }
            if (helpMap.containsKey(ruleId)) {
              fullMessage
                  .append("\n\nFor additional help see: ")
                  .append(this.getRuleHelpOrId(helpMap, ruleId));
            }
            violations.add(
                violationBuilder()
                    .setParser(Parser.SARIF)
                    .setFile(filename)
                    .setStartLine(startLine)
                    .setRule(ruleId)
                    .setMessage(fullMessage.toString().trim())
                    .setSeverity(this.toSeverity(level))
                    .setReporter(reporter)
                    .setSpecifics(specifics)
                    .build());
          }
        } else {
          violations.add(
              violationBuilder()
                  .setParser(Parser.SARIF)
                  .setFile(Violation.NO_FILE)
                  .setStartLine(Violation.NO_LINE)
                  .setRule(ruleId)
                  .setMessage(this.getRuleDescriptionOrMessage(ruleDescriptionMap, ruleId, message))
                  .setSeverity(this.toSeverity(level))
                  .setReporter(reporter)
                  .setSpecifics(specifics)
                  .build());
        }
      }
    }
    return violations;
  }

  private String getRuleDescriptionOrMessage(
      final Map<String, String> ruleDescriptionMap, final String ruleId, final String message) {
    final StringBuilder fullMessage = new StringBuilder();
    if (ruleDescriptionMap.containsKey(ruleId)) {
      fullMessage.append(ruleDescriptionMap.get(ruleId));
    }
    if (fullMessage.indexOf(message) < 0) {
      fullMessage.append("\n\n").append(message);
    }
    return fullMessage.toString();
  }

  private String getRuleHelpOrId(final Map<String, String> helpMap, final String ruleId) {
    if (helpMap.containsKey(ruleId)) {
      return helpMap.get(ruleId);
    }
    return ruleId;
  }

  /**
   * Returns the message text - favoring the markdown format.
   *
   * @param message the message from the Sarif result.
   * @return the message text which could be `null`.
   */
  protected String extractMessage(final Message message) {
    if (message == null) {
      return null;
    }
    String text = message.getMarkdown();
    if (Utils.isNullOrEmpty(text)) {
      text = message.getText();
    }
    return text;
  }

  private Map<String, String> extractHelpText(final Run run) {
    final Map<String, String> helpMap = new HashMap<>();
    if (run.getTool() != null
        && run.getTool().getDriver() != null
        && run.getTool().getDriver().getRules() != null) {
      for (final ReportingDescriptor r : run.getTool().getDriver().getRules()) {
        if (r.getHelp() != null && !isNullOrEmpty(r.getHelp().getMarkdown())) {
          helpMap.put(r.getId(), r.getHelp().getMarkdown());
        } else if (r.getHelp() != null && !isNullOrEmpty(r.getHelp().getText())) {
          helpMap.put(r.getId(), r.getHelp().getText());
        } else if (r.getFullDescription() != null
            && !isNullOrEmpty(r.getFullDescription().getMarkdown())) {
          helpMap.put(r.getId(), r.getFullDescription().getMarkdown());
        } else if (r.getFullDescription() != null
            && !isNullOrEmpty(r.getFullDescription().getText())) {
          helpMap.put(r.getId(), r.getFullDescription().getText());
        } else if (!isNullOrEmpty(r.getName())) {
          helpMap.put(r.getId(), r.getName());
        }
      }
    }
    return helpMap;
  }

  private Map<String, String> extractRuleDescriptionText(final Run run) {
    final Map<String, String> ruleDescriptionMap = new HashMap<>();
    if (run.getTool() != null
        && run.getTool().getDriver() != null
        && run.getTool().getDriver().getRules() != null) {
      for (final ReportingDescriptor r : run.getTool().getDriver().getRules()) {
        final StringBuilder fullMessage = new StringBuilder();

        if (r.getId() == null || isNullOrEmpty(r.getId())) {
          continue;
        }

        fullMessage.append(r.getId());
        if (r.getName() != null && !isNullOrEmpty(r.getName())) {
          fullMessage.append(": ").append(r.getName());
        }

        if (r.getShortDescription() != null
            && !isNullOrEmpty(r.getShortDescription().getMarkdown())) {
          fullMessage.append("\n\n").append(r.getShortDescription().getMarkdown());
        } else if (r.getShortDescription() != null
            && !isNullOrEmpty(r.getShortDescription().getText())) {
          fullMessage.append("\n\n").append(r.getShortDescription().getText());
        }

        ruleDescriptionMap.put(r.getId(), fullMessage.toString());
      }
    }
    return ruleDescriptionMap;
  }

  private SEVERITY toSeverity(final Level level) {
    if (level == Level.ERROR) {
      return SEVERITY.ERROR;
    }
    if (level == Level.WARNING) {
      return SEVERITY.WARN;
    }
    return SEVERITY.INFO;
  }
}
