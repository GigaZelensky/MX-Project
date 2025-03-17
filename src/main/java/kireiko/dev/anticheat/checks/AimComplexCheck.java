package kireiko.dev.anticheat.checks;

import kireiko.dev.anticheat.MX;
import kireiko.dev.anticheat.api.PacketCheckHandler;
import kireiko.dev.anticheat.api.events.RotationEvent;
import kireiko.dev.anticheat.api.events.UseEntityEvent;
import kireiko.dev.anticheat.api.player.PlayerProfile;
import kireiko.dev.millennium.math.Statistics;
import kireiko.dev.millennium.vectors.Pair;
import kireiko.dev.millennium.vectors.Vec2;
import kireiko.dev.millennium.vectors.Vec2f;
import kireiko.dev.millennium.vectors.Vec2i;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class AimComplexCheck implements PacketCheckHandler {
    private final List<Float> buffer;
    private final PlayerProfile profile;
    private final List<Vec2i> rotations, rotations2;
    private final List<Vec2> kireikoGeneric;
    private final List<Vec2f> rawRotations;
    private long lastAttack;
    private double oldShannonYaw, oldShannonPitch;
    public AimComplexCheck(PlayerProfile profile) {
        this.profile = profile;
        this.rotations = Collections.synchronizedList(new CopyOnWriteArrayList<>());
        this.rotations2 = Collections.synchronizedList(new CopyOnWriteArrayList<>());
        this.rawRotations = new CopyOnWriteArrayList<>();
        this.kireikoGeneric = new CopyOnWriteArrayList<>();
        this.lastAttack = 0L;
        this.buffer = new CopyOnWriteArrayList<>();
        this.oldShannonYaw = 0;
        this.oldShannonPitch = 0;
        for (int i = 0; i < 16; i++) this.buffer.add(0.0f);
    }
    @Override
    public void event(Object o) {
        if (o instanceof RotationEvent) {
            RotationEvent event = (RotationEvent) o;
            if (System.currentTimeMillis() > this.lastAttack + 3500) return;
            Vec2f delta = event.getDelta();
            this.rawRotations.add(delta);
            double gcdValue = Statistics.getGCDValue(0.5d) * 3;
            this.rotations.add(new Vec2i(
                            ((int) ((delta.getX() / gcdValue))),
                            ((int) ((delta.getY() / gcdValue)))));
            this.rotations2.add(new Vec2i(
                            ((int) ((delta.getX() / gcdValue))),
                            ((int) ((delta.getY() / gcdValue)))));
            if (this.rotations2.size() >= 10) {
                synchronized (rotations2) {
                    this.checkSpikes();
                }
            }
            if (this.rawRotations.size() >= 10) this.checkRaw();
        } else if (o instanceof UseEntityEvent) {
            UseEntityEvent event = (UseEntityEvent) o;
            boolean checkMobs = MX.getInstance().getConfig().getBoolean("checkMobs", false);
            if (event.isAttack() && (event.getTarget() instanceof Player || 
                                   (checkMobs && event.getTarget() instanceof LivingEntity))) {
                this.lastAttack = System.currentTimeMillis();
            }
        }
    }

    private void checkRaw() {
        { // uh
            final List<Float> x = new ArrayList<>(), y = new ArrayList<>();
            for (Vec2f vec2 : this.rawRotations) {
                x.add(vec2.getX());
                y.add(vec2.getY());
            }
            final int disX = Statistics.getDistinct(x);
            final double shannonYaw = Statistics.getShannonEntropy(x);
            final double shannonPitch = Statistics.getShannonEntropy(y);
            if (profile.getSensitivityProcessor().totalSensitivityClient > 85 &&
                            profile.getSensitivityProcessor().totalSensitivityClient < 170 &&
                            getDifference(shannonYaw, oldShannonYaw) < 1e-5
                            && getDifference(shannonPitch, oldShannonPitch) < 1e-5) {
                this.increaseBuffer(11, 1.0f);
                if (this.buffer.get(11) > 3)
                    profile.debug("&7Aim Perfect Shannon Entropy: " + this.buffer.get(11));
                if (this.buffer.get(11) > 30) {
                    profile.punish("Aim", "Entropy",
                                    "[Analysis] Perfect shannon entropy " + shannonYaw, 0.0f);
                    profile.setAttackBlockToTime(System.currentTimeMillis() + 5000);
                    this.buffer.set(11, 29f);
                }
            } else this.buffer.set(11, 0f);

            if ((disX < 8 && Math.abs(Statistics.getAverage(x)) > 2.5)) {
                this.increaseBuffer(9, 1.7f);
                profile.debug("&7Aim Invalid Distinct: " + this.buffer.get(9));
                if (this.buffer.get(9) >= 4.0f) {
                    profile.punish("Aim", "Distinct",
                                    "[Flaw] Invalid distinct", 0.5f);
                    profile.setAttackBlockToTime(System.currentTimeMillis() + 3000);
                    this.increaseBuffer(9, -0.5f);
                }
            } else this.increaseBuffer(9, -0.35f);
            this.oldShannonYaw = shannonYaw;
            this.oldShannonPitch = shannonPitch;
            /*
            profile.getPlayer().sendMessage("a: " + Statistics.getAverage(x)
                            + " " + Math.abs(Statistics.getAverage(y)));
             */
        }
        this.rawRotations.clear();
    }

    private void checkSpikes() {
        { // check spikes
            List<Integer>
                            gcdYaw = new ArrayList<>(),
                            gcdPitch = new ArrayList<>();
            for (Vec2i vec2i : this.rotations2) {
                gcdYaw.add(vec2i.getX());
                gcdPitch.add(vec2i.getY());
            }
            this.rotations2.clear();
            if (gcdYaw.isEmpty()) return;
            List<Double> yawX = Statistics.getOutliers(gcdYaw).getX();
            List<Double> yawY = Statistics.getOutliers(gcdYaw).getY();
            List<Double> pitchX = Statistics.getOutliers(gcdPitch).getX();
            List<Double> pitchY = Statistics.getOutliers(gcdPitch).getY();
            Vec2 kireikoGenericVec = new Vec2(Statistics.getKireikoGeneric(gcdYaw), Statistics.getKireikoGeneric(gcdPitch));
            double yawYMax = Math.max(Statistics.getMax(yawY), Math.abs(Statistics.getMin(yawY)));
            double yawYMin = Math.min(Statistics.getMax(yawY), Math.abs(Statistics.getMin(yawY)));
            /*
            profile.getPlayer().sendMessage(yawYMax + " " + yawYMin);
             */
            //profile.getPlayer().sendMessage("y: " + gcdYaw + " p: " + gcdPitch);
            { // kireiko generic
                this.kireikoGeneric.add(kireikoGenericVec);
                if (this.kireikoGeneric.size() >= 7) {
                    final List<Double> x = new ArrayList<>(), y = new ArrayList<>();
                    for (Vec2 vec2 : this.kireikoGeneric) {
                        x.add(vec2.getX());
                        y.add(vec2.getY());
                    }
                    /*
                    Using own generic formula
                    Clamping variation mul3 and kurtosis
                    And comparing with spikes
                     */
                    double xDev = Statistics.getStandardDeviation(x);
                    double yDev = Statistics.getStandardDeviation(y);
                    Pair<Double, Double> xSpikes = new Pair<>(Statistics.getMin(x), Statistics.getMax(x));
                    Pair<Double, Double> ySpikes = new Pair<>(Statistics.getMin(y), Statistics.getMax(y));
                    //profile.getPlayer().sendMessage("y: " + pearson + " " + regression);
                    { // check
                        if (xDev > 5 && xDev < 22 && xSpikes.getY() < 50) {
                            this.increaseBuffer(5, (Statistics.getAverage(x) < 6.0) ? 0 : (xDev < 10) ? 1.5f : 1.0f);
                            profile.debug("&7Machine Heart: " + this.buffer.get(5));
                            if (this.buffer.get(5) >= 7.0f) {
                                profile.punish("Aim", "MachineHeart",
                                                "Machine Heart " + (int) xDev, 2.5f);
                                this.buffer.set(5, 6.0f);
                            }
                        } else {
                            this.increaseBuffer(5, (xDev < 40 || xSpikes.getY() < 70) ? -0.4f : -0.8f);
                        }
                    }
                    this.kireikoGeneric.clear();
                }
            }
            { // let's check some aim flaws
                double devX = Statistics.getVariance(gcdYaw);
                double devY = Statistics.getVariance(gcdPitch);
                double min = Math.min(devX, devY);
                double max = Math.max(devX, devY);
                if ((min < 0.09 && max > 35 && Statistics.getMin(gcdPitch) != 0.0)) {
                    this.increaseBuffer(4, 1.0f);
                    profile.debug("&7Aim Randomizer flaw: " + this.buffer.get(4));
                    if (this.buffer.get(4) > 2.5f) {
                        // bluemouse...
                        profile.punish("Aim", "Randomizer", "[Analysis] Randomizer flaw", 3.5f);
                        this.buffer.set(4, 1.0f);
                    }
                } else this.increaseBuffer(4, -0.4f);
            }
            {
                if (!yawY.isEmpty() && (yawY.size() < 4)
                                && ((yawYMax == yawYMin && yawYMax > 55)
                                || (yawYMax > 60 && (yawYMin < yawYMax / 3)))) {
                    this.increaseBuffer(3, 1.25f);
                    profile.debug("&7Rough yaw(y) spikes: " + this.buffer.get(3));
                    if (this.buffer.get(3) > 4.0f) {
                        profile.punish("Aim", "Snap",
                                        "[Analysis] Rough yaw spikes " + yawY, 0.0f);
                        profile.setAttackBlockToTime(System.currentTimeMillis() + 3500);
                        this.increaseBuffer(3, -0.75f);
                    }
                } else {
                    this.increaseBuffer(3, -0.65f);
                }
            }
        }
    }
    private void increaseBuffer(int index, float v) {
        float r = this.buffer.get(index) + v;
        this.buffer.set(index, (r < 0) ? 0 : r);
    }
    private static double getDifference(double a, double b) {
        return Math.abs(Math.abs(a) - Math.abs(b));
    }
}
