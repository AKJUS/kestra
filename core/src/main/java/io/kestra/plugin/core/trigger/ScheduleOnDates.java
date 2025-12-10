package io.kestra.plugin.core.trigger;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.VoidOutput;
import io.kestra.core.models.triggers.*;
import io.kestra.core.runners.RunContext;
import io.kestra.core.validations.TimezoneId;
import io.kestra.core.scheduler.SchedulerClock;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;
import lombok.*;
import lombok.experimental.SuperBuilder;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import static io.kestra.core.utils.Rethrow.throwFunction;

@Slf4j
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Schedule a flow on specific dates."
)
@Plugin
public class ScheduleOnDates extends AbstractTrigger implements Schedulable, TriggerOutput<VoidOutput> {
    private static final String PLUGIN_PROPERTY_RECOVER_MISSED_SCHEDULES = "recoverMissedSchedules";

    @Schema(hidden = true)
    @Builder.Default
    @Null
    private final Duration interval = null;
    
    private Map<String, Object> inputs;
    
    @PluginProperty(dynamic = true)
    @Builder.Default
    private String timezone = ZoneId.systemDefault().toString();

    @NotNull
    private Property<List<ZonedDateTime>> dates;

    private RecoverMissedSchedules recoverMissedSchedules;

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext triggerContext) throws Exception {
        RunContext runContext = conditionContext.getRunContext();

        ZonedDateTime lastEvaluation = triggerContext.getDate();
        Optional<ZonedDateTime> nextDate = nextDate(runContext, date -> date.isEqual(lastEvaluation) || date.isAfter(lastEvaluation));

        if (nextDate.isPresent()) {
            log.info("Schedule execution on {}", nextDate.get());

            Execution execution = SchedulableExecutionFactory.createExecution(
                this,
                conditionContext,
                triggerContext,
                Collections.emptyMap(),
                nextDate.orElse(null)
            );

            return Optional.of(execution);
        }

        return Optional.empty();
    }

    @Override
    public ZonedDateTime nextEvaluationDate(ConditionContext conditionContext, Optional<? extends TriggerContext> last) {
        ZonedDateTime now = SchedulerClock.now();
        try {
            return last
                .map(throwFunction(context ->
                    nextDate(conditionContext.getRunContext(), date -> date.isAfter(context.getDate())).orElse(now.plusYears(1))
                ))
                .orElse(conditionContext.getRunContext()
                    .render(dates)
                    .asList(ZonedDateTime.class)
                    .stream()
                    .sorted()
                    .findFirst()
                    .orElse(now))
                .truncatedTo(ChronoUnit.SECONDS);
        } catch (IllegalVariableEvaluationException e) {
            log.warn("Failed to evaluate schedule dates for trigger '{}': {}", this.getId(), e.getMessage());
            return now.plusYears(1);
        }
    }
    
    @Override
    public ZonedDateTime nextEvaluationDate() {
        // TODO this may be the next date from now?
        return SchedulerClock.now();
    }

    @Override
    public ZonedDateTime previousEvaluationDate(ConditionContext conditionContext) throws IllegalVariableEvaluationException {
        // the previous date is "the previous date of the next date"
        ZonedDateTime now = ZonedDateTime.now();
        List<ZonedDateTime> previousDates = conditionContext.getRunContext().render(dates).asList(ZonedDateTime.class).stream()
            .sorted()
            .takeWhile(date -> date.isBefore(now))
            .toList()
            .reversed();

        return previousDates.isEmpty() ? ZonedDateTime.now() : previousDates.getFirst();
    }

    private ZonedDateTime withTimeZone(ZonedDateTime date) {
        if (this.timezone == null) {
            return date;
        }
        return date.withZoneSameInstant(ZoneId.of(this.timezone));
    }
    
    private Optional<ZonedDateTime> nextDate(RunContext runContext, Predicate<ZonedDateTime> predicate) throws IllegalVariableEvaluationException {
        return runContext.render(dates)
            .asList(ZonedDateTime.class).stream().sorted()
            .filter(predicate)
            .map(throwFunction(date -> timezone == null ? date : date.withZoneSameInstant(ZoneId.of(runContext.render(timezone)))))
            .findFirst()
            .map(date -> date.truncatedTo(ChronoUnit.SECONDS));
    }
}
