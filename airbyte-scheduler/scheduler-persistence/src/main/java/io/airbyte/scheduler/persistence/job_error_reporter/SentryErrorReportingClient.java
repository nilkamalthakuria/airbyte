/*
 * Copyright (c) 2022 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.scheduler.persistence.job_error_reporter;

import io.airbyte.config.FailureReason;
import io.airbyte.config.Metadata;
import io.airbyte.config.StandardWorkspace;
import io.sentry.Hub;
import io.sentry.IHub;
import io.sentry.NoOpHub;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import io.sentry.protocol.Message;
import io.sentry.protocol.SentryException;
import io.sentry.protocol.User;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SentryErrorReportingClient implements ErrorReportingClient {

  private final IHub sentryHub;

  SentryErrorReportingClient(final IHub sentryHub) {
    this.sentryHub = sentryHub;
  }

  public SentryErrorReportingClient(final String sentryDSN) {
    this(createSentryHubWithDSN(sentryDSN));
  }

  static IHub createSentryHubWithDSN(final String sentryDSN) {
    if (sentryDSN == null || sentryDSN.isEmpty()) {
      return NoOpHub.getInstance();
    }

    final SentryOptions options = new SentryOptions();
    options.setDsn(sentryDSN);
    options.setAttachStacktrace(false);
    options.setEnableUncaughtExceptionHandler(false);
    return new Hub(options);
  }

  /**
   * Reports a Connector Job FailureReason to Sentry
   *
   * @param workspace - Workspace where this failure occurred
   * @param failureReason - FailureReason to report
   * @param dockerImage - Tagged docker image that represents the release where this failure occurred
   * @param metadata - Extra metadata to set as tags on the event
   */
  @Override
  public void reportJobFailureReason(final StandardWorkspace workspace,
                                     final FailureReason failureReason,
                                     final String dockerImage,
                                     final Map<String, String> metadata) {
    final SentryEvent event = new SentryEvent();

    // Remove invalid characters from the release name, use @ so sentry knows how to grab the tag
    // e.g. airbyte/source-xyz:1.2.0 -> source-xyz@1.2.0
    // More info at https://docs.sentry.io/product/cli/releases/#creating-releases
    final String release = dockerImage.replace(":", "@").substring(dockerImage.lastIndexOf("/") + 1);
    event.setRelease(release);

    // enhance event fingerprint to ensure separate grouping per connector
    final String[] releaseParts = release.split("@");
    if (releaseParts.length > 0) {
      event.setFingerprints(List.of("{{ default }}", releaseParts[0]));
    }

    // set workspace as the user in sentry to get impact and priority
    final User sentryUser = new User();
    sentryUser.setId(String.valueOf(workspace.getWorkspaceId()));
    sentryUser.setUsername(workspace.getName());
    event.setUser(sentryUser);

    // set metadata as tags
    event.setTags(metadata);

    // set failure reason's internalMessage as event message
    // Sentry will use this to fuzzy-group if no stacktrace information is available
    final Message message = new Message();
    message.setFormatted(failureReason.getInternalMessage());
    event.setMessage(message);

    // events can come from any platform
    event.setPlatform("other");

    // attach failure reason stack trace
    final String failureStackTrace = failureReason.getStacktrace();
    if (failureStackTrace != null) {
      final List<SentryException> parsedExceptions = SentryExceptionHelper.buildSentryExceptions(failureStackTrace);
      if (parsedExceptions != null) {
        event.setExceptions(parsedExceptions);
      } else {
        event.setTag("stacktrace_parse_error", "1");
      }
    }

    sentryHub.configureScope(scope -> {
      final Map<String, String> failureReasonContext = new HashMap<>();
      failureReasonContext.put("internalMessage", failureReason.getInternalMessage());
      failureReasonContext.put("externalMessage", failureReason.getExternalMessage());
      failureReasonContext.put("stacktrace", failureReason.getStacktrace());
      failureReasonContext.put("timestamp", failureReason.getTimestamp().toString());

      final Metadata failureReasonMeta = failureReason.getMetadata();
      if (failureReasonMeta != null) {
        failureReasonContext.put("metadata", failureReasonMeta.toString());
      }

      scope.setContexts("Failure Reason", failureReasonContext);
    });

    sentryHub.captureEvent(event);
  }

}
