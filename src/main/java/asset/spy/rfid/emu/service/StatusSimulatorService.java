package asset.spy.rfid.emu.service;

import asset.spy.rfid.emu.dto.context.MessageSendContext;
import asset.spy.rfid.emu.dto.kafka.ProductStatusMessage;
import asset.spy.rfid.emu.dto.context.SimulationContext;
import asset.spy.rfid.emu.dto.context.TimeoutSettingContext;
import asset.spy.rfid.emu.model.ProductStatus;
import asset.spy.rfid.emu.service.strategy.StrategyType;
import asset.spy.rfid.emu.service.strategy.StateSequenceStrategyFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static java.util.concurrent.CompletableFuture.delayedExecutor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
public class StatusSimulatorService {

    private final KafkaProducerService kafkaProducerService;
    private final TaskExecutor kafkaTaskExecutor;
    private final StateSequenceStrategyFactory strategyFactory;

    public StatusSimulatorService(
            KafkaProducerService kafkaProducerService,
            @Qualifier("kafkaTaskExecutor") TaskExecutor kafkaTaskExecutor,
            StateSequenceStrategyFactory stateSequenceStrategyFactory) {
        this.kafkaProducerService = kafkaProducerService;
        this.kafkaTaskExecutor = kafkaTaskExecutor;
        this.strategyFactory = stateSequenceStrategyFactory;
    }

    public void simulate(SimulationContext context, TimeoutSettingContext timeoutSettings) {
        AtomicInteger index = new AtomicInteger(0);
        processStatuses(context, timeoutSettings, index);
    }

    private void processStatuses(SimulationContext context, TimeoutSettingContext timeoutSettings, AtomicInteger index) {
        List<ProductStatus> safeStatuses = getDefaultStatusSequence(context.statuses());

        if (!hasNextStatus(index.get(), safeStatuses)) {
            return;
        }

        ProductStatus status = getCurrentStatus(safeStatuses, index.get());
        ProductStatusMessage message = createMessage(context.itemId(), context.article(), status);
        MessageSendContext messageContext = new MessageSendContext(context.topic(), context.article().toString(),
                message, status, context, timeoutSettings, index);
        sendMessageWithCallback(messageContext);
    }

    private void sendMessageWithCallback(MessageSendContext context) {
        CompletableFuture<SendResult<String, ProductStatusMessage>> future =
                kafkaProducerService.sendMessage(context.topic(), context.key(), context.message());

        future.whenComplete((result, exception) -> {
            if (exception != null) {
                log.error("Failed to send message for item {}: {}", context.simulationContext().itemId(),
                        exception.getMessage());
                return;
            }

            if (!context.status().isFinal()) {
                scheduleNextStatus(context.simulationContext(), context.timeoutSettingContext(), context.index());
            } else {
                log.info("Completed status sequence for item: {}", context.simulationContext().itemId());
            }
        });
    }

    private List<ProductStatus> getDefaultStatusSequence(List<ProductStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return strategyFactory.getStrategy(StrategyType.FULL).buildSequence();
        }
        return statuses;
    }

    private boolean hasNextStatus(int currentIndex, List<ProductStatus> statuses) {
        return currentIndex >= 0 && currentIndex < statuses.size();
    }

    private ProductStatus getCurrentStatus(List<ProductStatus> statuses, int index) {
        return statuses.get(index);
    }

    private void scheduleNextStatus(SimulationContext context, TimeoutSettingContext timeoutSettings, AtomicInteger index) {
        int delay = calculateDelay(timeoutSettings.minTimeoutMin(), timeoutSettings.maxTimeoutMin());

        delayedExecutor(delay, TimeUnit.MILLISECONDS, kafkaTaskExecutor).execute(() -> {
            int nextIndex = index.incrementAndGet();
            processStatuses(context, timeoutSettings, new AtomicInteger(nextIndex));
        });
    }

    private ProductStatusMessage createMessage(String itemId, Long article, ProductStatus status) {
        return ProductStatusMessage.builder()
                .itemId(itemId)
                .article(article)
                .productStatus(status.getValue())
                .timestamp(OffsetDateTime.now(ZoneOffset.UTC))
                .build();
    }

    private int calculateDelay(int minTimeoutMin, int maxTimeoutMin) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int delayMin = Math.min(minTimeoutMin, maxTimeoutMin);
        int delayMax = Math.max(minTimeoutMin, maxTimeoutMin);

        delayMin = Math.max(delayMin, 1);

        int delayMinutes = delayMin + random.nextInt(delayMax - delayMin + 1);
        int delaySeconds = random.nextInt(60);

        return (delayMinutes * 60 + delaySeconds) * 1000;
    }
}
