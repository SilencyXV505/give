package ru.silencyxv.give;

import com.github.steveice10.mc.protocol.MinecraftConstants;
import com.github.steveice10.mc.protocol.MinecraftProtocol;
import com.github.steveice10.mc.protocol.data.game.entity.player.PositionElement;
import com.github.steveice10.mc.protocol.packet.ingame.client.player.ClientPlayerPositionPacket;
import com.github.steveice10.mc.protocol.packet.ingame.client.world.ClientTeleportConfirmPacket;
import com.github.steveice10.mc.protocol.packet.ingame.server.ServerJoinGamePacket;
import com.github.steveice10.mc.protocol.packet.ingame.server.entity.player.ServerPlayerPositionRotationPacket;
import com.github.steveice10.packetlib.Session;
import com.github.steveice10.packetlib.event.session.ConnectedEvent;
import com.github.steveice10.packetlib.event.session.DisconnectedEvent;
import com.github.steveice10.packetlib.event.session.DisconnectingEvent;
import com.github.steveice10.packetlib.event.session.PacketReceivedEvent;
import com.github.steveice10.packetlib.event.session.SessionAdapter;
import com.github.steveice10.packetlib.packet.Packet;
import com.github.steveice10.packetlib.tcp.TcpClientSession;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Launcher that connects the bot to mc.ds-join.ru and simulates falling physics to bypass bot filters.
 */
public final class DSJoinBot {
    private static final String DEFAULT_HOST = "mc.ds-join.ru";
    private static final int DEFAULT_PORT = 25565;
    private static final String DEFAULT_USERNAME = "GiveBot";

    private DSJoinBot() {
    }

    public static void main(String[] args) {
        String username = args.length > 0 && !args[0].isEmpty() ? args[0] : DEFAULT_USERNAME;

        MinecraftProtocol protocol = new MinecraftProtocol(username);
        Session session = new TcpClientSession(DEFAULT_HOST, DEFAULT_PORT, protocol);
        session.setFlag(MinecraftConstants.AUTOMATIC_KEEP_ALIVE_MANAGEMENT, true);

        PhysicsController physicsController = new PhysicsController(session);

        session.addListener(new SessionAdapter() {
            @Override
            public void connected(ConnectedEvent event) {
                System.out.println("Connected to " + DEFAULT_HOST + ":" + DEFAULT_PORT + " as " + username + '.');
            }

            @Override
            public void packetReceived(PacketReceivedEvent event) {
                Packet packet = event.getPacket();

                if (packet instanceof ServerJoinGamePacket) {
                    physicsController.onJoinGame();
                    return;
                }

                if (packet instanceof ServerPlayerPositionRotationPacket) {
                    physicsController.onPositionPacket((ServerPlayerPositionRotationPacket) packet);
                }
            }

            @Override
            public void disconnecting(DisconnectingEvent event) {
                physicsController.shutdown();
                if (event.getReason() != null) {
                    System.out.println("Disconnecting: "
                            + PlainTextComponentSerializer.plainText().serialize(event.getReason()));
                }
            }

            @Override
            public void disconnected(DisconnectedEvent event) {
                physicsController.shutdown();
                if (event.getReason() != null) {
                    System.out.println("Disconnected: "
                            + PlainTextComponentSerializer.plainText().serialize(event.getReason()));
                }
            }
        });

        System.out.println("Attempting to connect to " + DEFAULT_HOST + ":" + DEFAULT_PORT + "...");
        session.connect();
    }

    private static final class PhysicsController {
        private static final double GRAVITY = 0.08;
        private static final double DRAG = 0.98;
        private static final double FALL_HEIGHT = 3.25;
        private static final long TICK_INTERVAL_MS = 50L;

        private final Session session;
        private final ScheduledExecutorService scheduler;
        private final AtomicBoolean running = new AtomicBoolean(false);

        private volatile double x;
        private volatile double y;
        private volatile double z;
        private volatile double groundY;
        private volatile double velocityY;
        private volatile boolean hasSpawnPosition;
        private volatile boolean fallComplete;
        private volatile ScheduledFuture<?> fallTask;

        private PhysicsController(Session session) {
            this.session = session;
            this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "GiveBot-FallPhysics");
                thread.setDaemon(true);
                return thread;
            });
        }

        void onJoinGame() {
            // Reset state when the server sends a new join game packet (e.g. after respawn).
            this.hasSpawnPosition = false;
            this.fallComplete = false;
            this.velocityY = 0;
        }

        void onPositionPacket(ServerPlayerPositionRotationPacket packet) {
            updateCoordinates(packet);
            session.send(new ClientTeleportConfirmPacket(packet.getTeleportId()));

            if (!hasSpawnPosition) {
                hasSpawnPosition = true;
                groundY = y;
                startFalling();
            } else {
                // Keep ground height in sync after teleport corrections.
                groundY = y;
                if (!fallComplete && !running.get()) {
                    startFalling();
                }
            }
        }

        private void updateCoordinates(ServerPlayerPositionRotationPacket packet) {
            Set<PositionElement> relative = packet.getRelative().isEmpty()
                    ? EnumSet.noneOf(PositionElement.class)
                    : EnumSet.copyOf(packet.getRelative());

            if (relative.contains(PositionElement.X)) {
                this.x += packet.getX();
            } else {
                this.x = packet.getX();
            }

            if (relative.contains(PositionElement.Y)) {
                this.y += packet.getY();
            } else {
                this.y = packet.getY();
            }

            if (relative.contains(PositionElement.Z)) {
                this.z += packet.getZ();
            } else {
                this.z = packet.getZ();
            }
        }

        private void startFalling() {
            if (!running.compareAndSet(false, true)) {
                return;
            }

            this.fallComplete = false;
            this.velocityY = 0;
            this.y = this.groundY + FALL_HEIGHT;

            ScheduledFuture<?> currentTask = this.fallTask;
            if (currentTask != null && !currentTask.isDone()) {
                currentTask.cancel(false);
            }

            this.fallTask = scheduler.scheduleAtFixedRate(this::tick, 0L, TICK_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }

        private void tick() {
            if (fallComplete) {
                stopTask();
                return;
            }

            try {
                velocityY -= GRAVITY;
                velocityY *= DRAG;
                y += velocityY;

                boolean onGround = y <= groundY;
                if (onGround) {
                    y = groundY;
                }

                session.send(new ClientPlayerPositionPacket(onGround, x, y, z));

                if (onGround) {
                    fallComplete = true;
                    stopTask();
                }
            } catch (Throwable throwable) {
                fallComplete = true;
                stopTask();
                System.err.println("Falling physics encountered an error: " + throwable.getMessage());
            }
        }

        private void stopTask() {
            running.set(false);
            ScheduledFuture<?> currentTask = this.fallTask;
            if (currentTask != null && !currentTask.isDone()) {
                currentTask.cancel(false);
            }
        }

        void shutdown() {
            ScheduledFuture<?> currentTask = this.fallTask;
            if (currentTask != null) {
                currentTask.cancel(true);
            }
            scheduler.shutdownNow();
        }
    }
}
