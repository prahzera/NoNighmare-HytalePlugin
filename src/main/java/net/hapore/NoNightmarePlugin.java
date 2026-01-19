package net.hapore;

import java.util.logging.Level;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.annotation.Nonnull;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.hypixel.hytale.builtin.beds.sleep.components.PlayerSleep;
import com.hypixel.hytale.builtin.beds.sleep.components.PlayerSomnolence;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.console.ConsoleSender;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerSetupConnectEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;

/**
 * NoNightmarePlugin - Allows sleeping through the night with only a percentage
 * of players sleeping.
 * 
 * Uses a practical approach:
 * - Polls players every 2 seconds
 * - Detects sleeping via mount entity ID (players in beds are "mounted")
 * - Skips night via command execution when threshold is met
 */
public class NoNightmarePlugin extends JavaPlugin {

    private static final String PLUGIN_VERSION = "1.0.1";
    private static final String CONFIG_FILE_NAME = "nonightmare.json";
    private static final double DEFAULT_REQUIRED_PERCENT = 50.0;
    private static final int DEFAULT_DELAY_SECONDS = 2;
    private static final int DEFAULT_NIGHT_START_HOUR = 18;
    private static final int DEFAULT_NIGHT_END_HOUR = 4;
    private static final String DEFAULT_NIGHT_START_TIME = "18:00";
    private static final String DEFAULT_NIGHT_END_TIME = "04:47";
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private float sleepPercentageRequired = (float) (DEFAULT_REQUIRED_PERCENT / 100.0);
    private int skipDelaySeconds = DEFAULT_DELAY_SECONDS;
    private int nightStartHour = DEFAULT_NIGHT_START_HOUR;
    private int nightEndHour = DEFAULT_NIGHT_END_HOUR;
    private LocalTime nightStartTime = LocalTime.parse(DEFAULT_NIGHT_START_TIME);
    private LocalTime nightEndTime = LocalTime.parse(DEFAULT_NIGHT_END_TIME);
    private String messageSleepStatusTemplate = "";
    private String messageThresholdReachedTemplate = "";
    private String messageThresholdLostTemplate = "";
    private String messageNightSkippedTemplate = "";
    private String messageSleepNotAllowedTemplate = "";
    private ScheduledExecutorService scheduler;
    private boolean lastCheckWasSleeping = false;
    private int lastSleepingPlayers = -1;
    private int lastTotalPlayers = -1;
    private long thresholdReachedAtMillis = 0L;
    private final Set<UUID> notifiedDaySleepers = new HashSet<>();
    private long ignoreDaySleepUntilMillis = 0L;

    public NoNightmarePlugin(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        super.setup();

        loadConfig();
        registerCommands();

        getLogger().at(Level.INFO).log("NoNightmare v" + PLUGIN_VERSION + " iniciado.");
        getLogger().at(Level.FINE).log("Requerido: " + (sleepPercentageRequired * 100) + "% de jugadores.");
        getLogger().at(Level.FINE).log("Delay: " + skipDelaySeconds + "s. Noche: " + nightStartTime + " - " + nightEndTime + ".");

        var eventRegistry = getEventRegistry();
        eventRegistry.register(PlayerSetupConnectEvent.class, (PlayerSetupConnectEvent event) -> {
            getLogger().at(Level.INFO).log("Player connected: " + event.getUsername());
        });
    }

    @Override
    protected void start() {
        super.start();

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "NoNightmare-SleepPoller");
            t.setDaemon(true);
            return t;
        });
        

        scheduler.scheduleAtFixedRate(() -> pollSleep(), 1, 1, TimeUnit.SECONDS);

        getLogger().at(Level.FINE).log("Sleep poller activo (cada 1s).");
    }

    @Override
    protected void shutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        super.shutdown();
    }

    private void loadConfig() {
        Path dataDir = getDataDirectory();
        Path configPath = dataDir.resolve(CONFIG_FILE_NAME);
        PluginConfig config = new PluginConfig();
        PluginConfig defaultConfig = new PluginConfig();
        boolean shouldWriteDefault = false;
        boolean shouldUpdateConfig = false;

        try {
            Files.createDirectories(dataDir);
            if (Files.exists(configPath)) {
                String json = Files.readString(configPath, StandardCharsets.UTF_8);
                try {
                    PluginConfig parsed = gson.fromJson(json, PluginConfig.class);
                    if (parsed != null) {
                        config = parsed;
                    }
                } catch (JsonSyntaxException e) {
                    getLogger().at(Level.WARNING).log("Config inválida, se usan valores por defecto.");
                    shouldWriteDefault = true;
                }
            } else {
                shouldWriteDefault = true;
            }
        } catch (Exception e) {
            getLogger().at(Level.WARNING).log("No se pudo leer la config: " + e.getMessage());
            shouldWriteDefault = true;
        }

        double percent = config.requiredSleepPercent;
        if (Double.isNaN(percent) || Double.isInfinite(percent)) {
            percent = DEFAULT_REQUIRED_PERCENT;
        }
        percent = Math.max(0.0, Math.min(100.0, percent));
        sleepPercentageRequired = (float) (percent / 100.0);

        skipDelaySeconds = Math.max(0, config.skipDelaySeconds);
        nightStartHour = clampHour(config.nightStartHour, DEFAULT_NIGHT_START_HOUR);
        nightEndHour = clampHour(config.nightEndHour, DEFAULT_NIGHT_END_HOUR);
        nightStartTime = parseTimeOrFallback(config.nightStartTime, nightStartHour, DEFAULT_NIGHT_START_TIME);
        nightEndTime = parseTimeOrFallback(config.nightEndTime, nightEndHour, DEFAULT_NIGHT_END_TIME);

        if (config.skipDelaySeconds != skipDelaySeconds) {
            config.skipDelaySeconds = skipDelaySeconds;
            shouldUpdateConfig = true;
        }
        if (config.nightStartHour != nightStartHour) {
            config.nightStartHour = nightStartHour;
            shouldUpdateConfig = true;
        }
        if (config.nightEndHour != nightEndHour) {
            config.nightEndHour = nightEndHour;
            shouldUpdateConfig = true;
        }
        if (config.nightStartTime == null || config.nightStartTime.isBlank()) {
            config.nightStartTime = DEFAULT_NIGHT_START_TIME;
            shouldUpdateConfig = true;
        }
        if (config.nightEndTime == null || config.nightEndTime.isBlank()) {
            config.nightEndTime = DEFAULT_NIGHT_END_TIME;
            shouldUpdateConfig = true;
        }

        messageSleepStatusTemplate = config.messageSleepStatus != null ? config.messageSleepStatus : defaultConfig.messageSleepStatus;
        messageThresholdReachedTemplate = config.messageThresholdReached != null ? config.messageThresholdReached : defaultConfig.messageThresholdReached;
        messageThresholdLostTemplate = config.messageThresholdLost != null ? config.messageThresholdLost : defaultConfig.messageThresholdLost;
        messageNightSkippedTemplate = config.messageNightSkipped != null ? config.messageNightSkipped : defaultConfig.messageNightSkipped;
        messageSleepNotAllowedTemplate = config.messageSleepNotAllowed != null ? config.messageSleepNotAllowed : defaultConfig.messageSleepNotAllowed;

        if (config.messageSleepStatus == null) {
            config.messageSleepStatus = messageSleepStatusTemplate;
            shouldUpdateConfig = true;
        }
        if (config.messageThresholdReached == null) {
            config.messageThresholdReached = messageThresholdReachedTemplate;
            shouldUpdateConfig = true;
        }
        if (config.messageThresholdLost == null) {
            config.messageThresholdLost = messageThresholdLostTemplate;
            shouldUpdateConfig = true;
        }
        if (config.messageNightSkipped == null) {
            config.messageNightSkipped = messageNightSkippedTemplate;
            shouldUpdateConfig = true;
        }
        if (config.messageSleepNotAllowed == null) {
            config.messageSleepNotAllowed = messageSleepNotAllowedTemplate;
            shouldUpdateConfig = true;
        }

        if (shouldWriteDefault || shouldUpdateConfig) {
            try {
                String json = gson.toJson(config);
                Files.writeString(configPath, json, StandardCharsets.UTF_8);
            } catch (Exception e) {
                getLogger().at(Level.WARNING).log("No se pudo escribir la config: " + e.getMessage());
            }
        }
    }

    private void reloadConfigAndState() {
        loadConfig();
        lastCheckWasSleeping = false;
        lastSleepingPlayers = -1;
        lastTotalPlayers = -1;
        thresholdReachedAtMillis = 0L;
    }

    private void registerCommands() {
        var registry = getCommandRegistry();
        if (registry == null) {
            return;
        }

        AbstractCommand root = new AbstractCommand("nonightmare", "Comandos de NoNightmare", false) {
            @Override
            protected CompletableFuture<Void> execute(CommandContext context) {
                sendHelp(context);
                return CompletableFuture.completedFuture(null);
            }
        };

        AbstractCommand reload = new AbstractCommand("reload", "Recarga la configuración", false) {
            @Override
            protected CompletableFuture<Void> execute(CommandContext context) {
                reloadConfigAndState();
                Message prefix = Message.join(
                        Message.raw("[").color("#6B7280"),
                        Message.raw("NoNightmare").color("#7C3AED").bold(true),
                        Message.raw("] ").color("#6B7280"));
                Message body = Message.raw("Configuración recargada.").color("#22C55E").bold(true);
                context.sendMessage(Message.join(prefix, body));
                getLogger().at(Level.INFO).log("Configuración recargada por comando.");
                return CompletableFuture.completedFuture(null);
            }
        };
        reload.requirePermission(getBasePermission() + ".reload");

        AbstractCommand setPercent = new AbstractCommand("setpercent", "Set required sleep percent", false) {
            private final RequiredArg<Double> percentArg =
                    withRequiredArg("percent", "Required sleep percentage (0-100)", ArgTypes.DOUBLE);

            @Override
            protected CompletableFuture<Void> execute(CommandContext context) {
                Double value = context.get(percentArg);
                if (value == null) {
                    sendHelp(context);
                    return CompletableFuture.completedFuture(null);
                }
                double clamped = Math.max(0.0, Math.min(100.0, value));
                sleepPercentageRequired = (float) (clamped / 100.0);
                saveConfig();

                Message prefix = Message.join(
                        Message.raw("[").color("#6B7280"),
                        Message.raw("NoNightmare").color("#7C3AED").bold(true),
                        Message.raw("] ").color("#6B7280"));
                Message body = Message.raw("Sleep percent set to " + String.format("%.1f", clamped) + "%.")
                        .color("#22C55E")
                        .bold(true);
                context.sendMessage(Message.join(prefix, body));
                return CompletableFuture.completedFuture(null);
            }
        };
        setPercent.requirePermission(getBasePermission() + ".setpercent");

        AbstractCommand setDelay = new AbstractCommand("setdelay", "Set delay before sunrise", false) {
            private final RequiredArg<Integer> secondsArg =
                    withRequiredArg("seconds", "Delay in seconds", ArgTypes.INTEGER);

            @Override
            protected CompletableFuture<Void> execute(CommandContext context) {
                Integer value = context.get(secondsArg);
                if (value == null) {
                    sendHelp(context);
                    return CompletableFuture.completedFuture(null);
                }
                int clamped = Math.max(0, value);
                skipDelaySeconds = clamped;
                saveConfig();

                Message prefix = Message.join(
                        Message.raw("[").color("#6B7280"),
                        Message.raw("NoNightmare").color("#7C3AED").bold(true),
                        Message.raw("] ").color("#6B7280"));
                Message body = Message.raw("Delay set to " + clamped + "s.")
                        .color("#22C55E")
                        .bold(true);
                context.sendMessage(Message.join(prefix, body));
                return CompletableFuture.completedFuture(null);
            }
        };
        setDelay.requirePermission(getBasePermission() + ".setdelay");

        AbstractCommand help = new AbstractCommand("help", "Show help", false) {
            @Override
            protected CompletableFuture<Void> execute(CommandContext context) {
                sendHelp(context);
                return CompletableFuture.completedFuture(null);
            }
        };

        root.addSubCommand(reload);
        root.addSubCommand(setPercent);
        root.addSubCommand(setDelay);
        root.addSubCommand(help);
        registry.registerCommand(root);
    }

    private void sendHelp(CommandContext context) {
        Message header = Message.join(
                Message.raw("[").color("#6B7280"),
                Message.raw("NoNightmare").color("#7C3AED").bold(true),
                Message.raw("] ").color("#6B7280"),
                Message.raw("Commands").color("#E5E7EB").bold(true));
        context.sendMessage(header);
        context.sendMessage(Message.raw("/nonightmare help - Show this help.").color("#E5E7EB"));
        context.sendMessage(Message.raw("/nonightmare reload - Reload the plugin configuration.").color("#E5E7EB"));
        context.sendMessage(Message.raw("/nonightmare setpercent <0-100> - Set required sleep percentage.").color("#E5E7EB"));
        context.sendMessage(Message.raw("/nonightmare setdelay <seconds> - Set delay before sunrise (recommend 2-3s max).")
                .color("#E5E7EB"));
    }

    private void saveConfig() {
        try {
            PluginConfig config = buildCurrentConfig();
            Path dataDir = getDataDirectory();
            Files.createDirectories(dataDir);
            Path configPath = dataDir.resolve(CONFIG_FILE_NAME);
            String json = gson.toJson(config);
            Files.writeString(configPath, json, StandardCharsets.UTF_8);
        } catch (Exception e) {
            getLogger().at(Level.WARNING).log("No se pudo guardar la config: " + e.getMessage());
        }
    }

    private PluginConfig buildCurrentConfig() {
        PluginConfig config = new PluginConfig();
        config.requiredSleepPercent = sleepPercentageRequired * 100.0;
        config.skipDelaySeconds = skipDelaySeconds;
        config.nightStartHour = nightStartHour;
        config.nightEndHour = nightEndHour;
        config.nightStartTime = nightStartTime.toString();
        config.nightEndTime = nightEndTime.toString();
        config.messageSleepStatus = messageSleepStatusTemplate;
        config.messageThresholdReached = messageThresholdReachedTemplate;
        config.messageThresholdLost = messageThresholdLostTemplate;
        config.messageNightSkipped = messageNightSkippedTemplate;
        config.messageSleepNotAllowed = messageSleepNotAllowedTemplate;
        return config;
    }

    /**
     * Poll all players to detect sleeping and skip night if threshold is met.
     * Uses mount entity ID as sleep indicator (players in beds are mounted to bed
     * entity).
     */
    private void pollSleep() {
        try {
            // Get server instance
            HytaleServer server = HytaleServer.get();
            if (server == null) {
                return;
            }

            // Get the default world
            Universe universe = Universe.get();
            if (universe == null) {
                return;
            }

            World selectedWorld = universe.getDefaultWorld();
            if (selectedWorld == null) {
                selectedWorld = universe.getWorld("default");
            }
            if (selectedWorld == null) {
                return;
            }

            World world = selectedWorld;
            world.execute(() -> checkSleep(world));
        } catch (Exception e) {
            getLogger().at(Level.WARNING).log("Error in sleep polling: " + e.getMessage());
        }
    }

    @SuppressWarnings("removal")
    private void checkSleep(World world) {
        // Get all players
        var players = world.getPlayers();
        int totalPlayers = players.size();

        if (totalPlayers == 0) {
            return; // No players online
        }

        int sleepingPlayers = 0;
        List<Player> sleepingPlayersList = new ArrayList<>();
        Set<UUID> currentSleepingIds = new HashSet<>();
        var store = world.getEntityStore().getStore();

        for (Player player : players) {
            boolean isSleeping = false;

            try {
                int mountId = player.getMountEntityId();
                if (mountId > 0) {
                    isSleeping = true;
                }
            } catch (Exception e) {
                // Ignore mount failures
            }

            if (!isSleeping) {
                try {
                    var ref = player.getReference();
                    if (ref != null) {
                        var somnolence = store.getComponent(ref, PlayerSomnolence.getComponentType());
                        if (somnolence != null) {
                            PlayerSleep state = somnolence.getSleepState();
                            if (state instanceof PlayerSleep.Slumber || state instanceof PlayerSleep.NoddingOff) {
                                isSleeping = true;
                            }
                        }
                    }
                } catch (Exception e) {
                    // Ignore component failures
                }
            }

            if (isSleeping) {
                sleepingPlayers++;
                sleepingPlayersList.add(player);
                currentSleepingIds.add(player.getUuid());
            }
        }

        float sleepPercentage = (float) sleepingPlayers / totalPlayers;
        boolean isNight = isNightTime(world);

        if (sleepingPlayers > 0) {
            if (!lastCheckWasSleeping) {
                getLogger().at(Level.FINE).log(String.format(
                        "Sleep detectado: %d/%d (%.1f%%). Noche=%s",
                        sleepingPlayers, totalPlayers, sleepPercentage * 100, isNight));
            }
            lastCheckWasSleeping = true;
        } else {
            lastCheckWasSleeping = false;
        }

        if (sleepingPlayers == 0) {
            notifiedDaySleepers.clear();
        } else {
            notifiedDaySleepers.retainAll(currentSleepingIds);
        }

        if (isNight && (sleepingPlayers != lastSleepingPlayers || totalPlayers != lastTotalPlayers)) {
            sendSleepStatusMessage(world, sleepingPlayers, totalPlayers, sleepPercentage);
            lastSleepingPlayers = sleepingPlayers;
            lastTotalPlayers = totalPlayers;
        }

        if (!isNight && sleepingPlayers > 0) {
            if (System.currentTimeMillis() < ignoreDaySleepUntilMillis) {
                lastSleepingPlayers = sleepingPlayers;
                lastTotalPlayers = totalPlayers;
                thresholdReachedAtMillis = 0L;
                return;
            }
            for (Player player : sleepingPlayersList) {
                UUID playerId = player.getUuid();
                if (!notifiedDaySleepers.contains(playerId)) {
                    sendSleepNotAllowedMessage(player);
                    notifiedDaySleepers.add(playerId);
                }
            }
            lastSleepingPlayers = sleepingPlayers;
            lastTotalPlayers = totalPlayers;
            thresholdReachedAtMillis = 0L;
            return;
        }

        boolean thresholdMet = sleepPercentage >= sleepPercentageRequired && sleepingPlayers > 0;

        if (!isNight) {
            thresholdReachedAtMillis = 0L;
            return;
        }

        if (thresholdMet) {
            if (thresholdReachedAtMillis == 0L) {
                thresholdReachedAtMillis = System.currentTimeMillis();
                sendThresholdReachedMessage(world, skipDelaySeconds);
            }
        } else if (thresholdReachedAtMillis != 0L) {
            thresholdReachedAtMillis = 0L;
            sendThresholdLostMessage(world);
        }

        if (thresholdReachedAtMillis != 0L) {
            long elapsed = System.currentTimeMillis() - thresholdReachedAtMillis;
            if (elapsed >= (long) skipDelaySeconds * 1000L) {
                try {
                    CommandManager.get().handleCommand(ConsoleSender.INSTANCE, "time set day");
                    sendNightSkippedMessage(world, sleepingPlayers, totalPlayers, sleepPercentage);
                    getLogger().at(Level.INFO).log("Noche omitida por sueño suficiente.");
                    lastCheckWasSleeping = false;
                    ignoreDaySleepUntilMillis = System.currentTimeMillis() + 2000L;
                    thresholdReachedAtMillis = 0L;
                } catch (Exception e) {
                    getLogger().at(Level.WARNING).log("Failed to skip night: " + e.getMessage());
                }
            }
        }
    }

    public float getSleepPercentageRequired() {
        return sleepPercentageRequired;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    private static final class PluginConfig {
        public double requiredSleepPercent = DEFAULT_REQUIRED_PERCENT;
        public int skipDelaySeconds = DEFAULT_DELAY_SECONDS;
        public int nightStartHour = DEFAULT_NIGHT_START_HOUR;
        public int nightEndHour = DEFAULT_NIGHT_END_HOUR;
        public String nightStartTime = DEFAULT_NIGHT_START_TIME;
        public String nightEndTime = DEFAULT_NIGHT_END_TIME;
        public String messageSleepStatus = "{#6B7280}[{#7C3AED}{bold}NoNightmare{/bold}{#6B7280}] {#E5E7EB}Durmiendo: {#22C55E}{bold}{sleeping}{/bold}{#9CA3AF}/{#E5E7EB}{total} {#9CA3AF}({#38BDF8}{bold}{percent}{/bold}%{#9CA3AF} / {#F59E0B}{bold}{required}{/bold}%{#9CA3AF})";
        public String messageThresholdReached = "{#6B7280}[{#7C3AED}{bold}NoNightmare{/bold}{#6B7280}] {#E5E7EB}Umbral alcanzado. Amanecerá en {#F59E0B}{bold}{delay}{/bold}{#F59E0B}s";
        public String messageThresholdLost = "{#6B7280}[{#7C3AED}{bold}NoNightmare{/bold}{#6B7280}] {#EF4444}{bold}El umbral dejó de cumplirse.{/bold}";
        public String messageNightSkipped = "{#6B7280}[{#7C3AED}{bold}NoNightmare{/bold}{#6B7280}] {#22C55E}{bold}¡Buenos días!{/bold} {#E5E7EB}Se alcanzó {#38BDF8}{bold}{percent}{/bold}% {#9CA3AF}({#22C55E}{bold}{sleeping}{/bold}{#9CA3AF}/{#E5E7EB}{total}{#9CA3AF})";
        public String messageSleepNotAllowed = "{#6B7280}[{#7C3AED}{bold}NoNightmare{/bold}{#6B7280}] {#F59E0B}{bold}Solo puedes dormir para hacer de Día durante la noche.{/bold}";
    }

    private int clampHour(int value, int fallback) {
        if (value < 0 || value > 23) {
            return fallback;
        }
        return value;
    }

    private LocalTime parseTimeOrFallback(String value, int hourFallback, String defaultTime) {
        if (value != null && !value.isBlank()) {
            try {
                return LocalTime.parse(value.trim());
            } catch (Exception e) {
                // Fall through to fallback
            }
        }
        try {
            return LocalTime.of(hourFallback, 0);
        } catch (Exception e) {
            return LocalTime.parse(defaultTime);
        }
    }

    private boolean isNightTime(World world) {
        try {
            var store = world.getEntityStore().getStore();
            WorldTimeResource time = store.getResource(WorldTimeResource.getResourceType());
            if (time == null) {
                return true;
            }
            LocalTime current = time.getGameDateTime().toLocalTime();
            if (nightStartTime.equals(nightEndTime)) {
                return false;
            }
            if (!nightStartTime.isAfter(nightEndTime)) {
                return !current.isBefore(nightStartTime) && current.isBefore(nightEndTime);
            }
            return !current.isBefore(nightStartTime) || current.isBefore(nightEndTime);
        } catch (Exception e) {
            return true;
        }
    }

    private void sendSleepStatusMessage(World world, int sleepingPlayers, int totalPlayers, float sleepPercentage) {
        Map<String, String> vars = baseVariables(sleepingPlayers, totalPlayers, sleepPercentage);
        world.sendMessage(buildMessageFromTemplate(messageSleepStatusTemplate, vars));
    }

    private void sendThresholdReachedMessage(World world, int delaySeconds) {
        Map<String, String> vars = baseVariables(0, 0, 0.0f);
        vars.put("delay", String.valueOf(delaySeconds));
        world.sendMessage(buildMessageFromTemplate(messageThresholdReachedTemplate, vars));
    }

    private void sendThresholdLostMessage(World world) {
        Map<String, String> vars = baseVariables(0, 0, 0.0f);
        world.sendMessage(buildMessageFromTemplate(messageThresholdLostTemplate, vars));
    }

    private void sendSleepNotAllowedMessage(Player player) {
        Map<String, String> vars = baseVariables(0, 0, 0.0f);
        player.sendMessage(buildMessageFromTemplate(messageSleepNotAllowedTemplate, vars));
    }

    private void sendNightSkippedMessage(World world, int sleepingPlayers, int totalPlayers, float sleepPercentage) {
        Map<String, String> vars = baseVariables(sleepingPlayers, totalPlayers, sleepPercentage);
        world.sendMessage(buildMessageFromTemplate(messageNightSkippedTemplate, vars));
    }

    private Map<String, String> baseVariables(int sleepingPlayers, int totalPlayers, float sleepPercentage) {
        Map<String, String> vars = new HashMap<>();
        vars.put("sleeping", String.valueOf(sleepingPlayers));
        vars.put("total", String.valueOf(totalPlayers));
        vars.put("percent", String.format("%.1f", sleepPercentage * 100));
        vars.put("required", String.format("%.1f", sleepPercentageRequired * 100));
        vars.put("delay", String.valueOf(skipDelaySeconds));
        return vars;
    }

    private Message buildMessageFromTemplate(String template, Map<String, String> vars) {
        if (template == null || template.isBlank()) {
            return Message.raw("");
        }

        String resolved = template;
        for (var entry : vars.entrySet()) {
            resolved = resolved.replace("{" + entry.getKey() + "}", entry.getValue());
        }

        List<Message> parts = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        String color = null;
        boolean bold = false;
        boolean italic = false;
        boolean mono = false;

        int i = 0;
        while (i < resolved.length()) {
            int open = resolved.indexOf('{', i);
            if (open == -1) {
                buffer.append(resolved.substring(i));
                break;
            }
            int close = resolved.indexOf('}', open);
            if (close == -1) {
                buffer.append(resolved.substring(i));
                break;
            }
            buffer.append(resolved.substring(i, open));
            if (buffer.length() > 0) {
                parts.add(applyStyle(Message.raw(buffer.toString()), color, bold, italic, mono));
                buffer.setLength(0);
            }

            String token = resolved.substring(open + 1, close).trim();
            if (token.startsWith("#")) {
                color = token;
            } else if ("bold".equalsIgnoreCase(token)) {
                bold = true;
            } else if ("/bold".equalsIgnoreCase(token)) {
                bold = false;
            } else if ("italic".equalsIgnoreCase(token)) {
                italic = true;
            } else if ("/italic".equalsIgnoreCase(token)) {
                italic = false;
            } else if ("mono".equalsIgnoreCase(token)) {
                mono = true;
            } else if ("/mono".equalsIgnoreCase(token)) {
                mono = false;
            } else if ("reset".equalsIgnoreCase(token)) {
                color = null;
                bold = false;
                italic = false;
                mono = false;
            } else {
                buffer.append('{').append(token).append('}');
            }

            i = close + 1;
        }

        if (buffer.length() > 0) {
            parts.add(applyStyle(Message.raw(buffer.toString()), color, bold, italic, mono));
        }

        return Message.join(parts.toArray(new Message[0]));
    }

    private Message applyStyle(Message message, String color, boolean bold, boolean italic, boolean mono) {
        if (color != null) {
            message.color(color);
        }
        if (bold) {
            message.bold(true);
        }
        if (italic) {
            message.italic(true);
        }
        if (mono) {
            message.monospace(true);
        }
        return message;
    }
}
