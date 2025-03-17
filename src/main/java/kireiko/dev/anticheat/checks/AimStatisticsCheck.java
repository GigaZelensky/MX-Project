package kireiko.dev.anticheat.checks;

import kireiko.dev.anticheat.MX;
import kireiko.dev.anticheat.api.PacketCheckHandler;
import kireiko.dev.anticheat.api.events.RotationEvent;
import kireiko.dev.anticheat.api.events.UseEntityEvent;
import kireiko.dev.anticheat.api.player.PlayerProfile;
import kireiko.dev.millennium.math.Statistics;
import kireiko.dev.millennium.vectors.Vec2f;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

public class AimStatisticsCheck implements PacketCheckHandler {
    private final List<Float> buffer;
    private final PlayerProfile profile;
    private final List<Vec2f> rawRotations;
    private final List<Double> shannonAnalysis;
    private long lastAttack;
    public AimStatisticsCheck(PlayerProfile profile) {
        this.profile = profile;
        this.rawRotations = new CopyOnWriteArrayList<>();
        this.lastAttack = 0L;
        this.buffer = new CopyOnWriteArrayList<>();
        this.shannonAnalysis = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 16; i++) this.buffer.add(0.0f);
    }
    @Override
    public void event(Object o) {
        if (o instanceof RotationEvent) {
            RotationEvent event = (RotationEvent) o;
            if (System.currentTimeMillis() > this.lastAttack + 3500) return;
            Vec2f delta = event.getDelta();
            this.rawRotations.add(delta);
            if (this.rawRotations.size() >= 25) this.checkRaw();
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
        float total = 0;
        { // uh
            //profile.getPlayer().sendMessage("i: " + profile.getSensitivityProcessor().getSensitivity());
            List<Float> x = new ArrayList<>(), y = new ArrayList<>();
            for (Vec2f vec2 : this.rawRotations) {
                x.add(vec2.getX());
                y.add(vec2.getY());
            }
            final List<Double> zFactorYaw = Statistics.getZScoreOutliers(x, 2.0);
            final List<Float> jiffYaw = Statistics.getJiffDelta(x, 5);
            final List<Float> jiffPitch = Statistics.getJiffDelta(y, 5);
            final List<Float> jiffOmni = new ArrayList<>();
            for (int i = 0; i < Math.min(jiffYaw.size(), jiffPitch.size()); i++) {
                jiffOmni.add(jiffYaw.get(i) / (jiffPitch.get(i)));
            }
            { // omni check
                int infs = 0;
                for (float j : jiffOmni) if (Float.isInfinite(j)) infs++;
                final double iqr = Statistics.getIQR(jiffOmni);
                if (iqr > 12.5 && iqr < 96 && infs > 0) {
                    this.increaseBuffer(8, (iqr > 20) ? 1.4f : 0.8f);
                    this.profile.debug("&7Aim IQR: " + this.buffer.get(8) + " infs: " + infs);
                    if (this.buffer.get(8) > 11.0f) {
                        this.profile.punish("Aim", "IQR", "[Statistics] IQR " + iqr, 2.5f);
                        this.buffer.set(8, 9.5f);
                    }
                } else if (iqr < 13 || infs == 0) {
                    if (iqr < 7) {
                        this.increaseBuffer(8, -5.0f);
                    } else this.increaseBuffer(8, -3.5f);
                }
            }
            //profile.getPlayer().sendMessage("j: " + Arrays.toString(jiffOmni.toArray()));
            final double kTest = Statistics.kolmogorovSmirnovTest(Statistics.getJiffDelta(x, 6), Function.identity());
            { // Kolmogorov Smirnov Test
                if (kTest > 7 && Math.abs(Statistics.getAverage(x)) < 13) {
                    this.increaseBuffer(2, (kTest > 100)
                                    ? 2.0f : (kTest > 45) ? 1.25f : 0.1f);
                    if (kTest > 10) total++;
                    profile.debug("&7Aim Kolmogorov Smirnov Test: " + kTest + " VL: " + this.buffer.get(2));
                    if (this.buffer.get(2) >= 6 && kTest > 90) {
                        this.profile.punish("Aim", "KS Test", "[Statistics] Kolmogorov Smirnov Test " + kTest, 0.0f);
                        this.profile.setAttackBlockToTime(System.currentTimeMillis() + 4000);
                        this.buffer.set(2, 5f);
                    }
                } else {
                    this.increaseBuffer(2, -2f);
                }
            }
            shannonAnalysis.add(Statistics.getShannonEntropy(jiffYaw));
            if (shannonAnalysis.size() > 9) {
                final Set<Double> uniq = new HashSet<>(shannonAnalysis);
                final double diff =
                getDifference(Statistics.getMin(uniq), Statistics.getMax(uniq));
                if (uniq.size() <= 5 && uniq.size() > 3 && diff < 0.38) {
                   // this.profile.punish("Aim", "[Statistics] AimBot Entropy Heuristic " + uniq.size() + " " + diff, 3.0f);
                }
                shannonAnalysis.clear();
            }
            int jiffPatterns = 0;
            for (int i = 0; i < jiffYaw.size(); i++) {
                float f = jiffYaw.get(i);
                if (!String.valueOf(f).contains("E") || f == 0) continue;
                for (int r = 0; r < jiffYaw.size(); r++) {
                    if (r == i) continue;
                    if (f == jiffYaw.get(r))
                        jiffPatterns++;
                }
            }
            if (jiffPatterns > 2 && Statistics.getAverage(x) > 3.0
                            && jiffPatterns != 6 && jiffPatterns != 12 && jiffPatterns != 4) {
                this.profile.punish("Aim", "Pattern", "[Statistics] AimBot pattern " + jiffPatterns, 3.0f);
                this.profile.setAttackBlockToTime(System.currentTimeMillis() + 4600L);
            }
            //profile.getPlayer().sendMessage("j: " + Arrays.toString(jiffYaw.toArray()));

            boolean positive = false, negative = false;
            for (double d : zFactorYaw) {
                if (d > 12) positive = true;
                if (d < -12) negative = true;
            }
            if (zFactorYaw.size() == 2 && positive && negative
                            && Statistics.getMax(zFactorYaw) < 55) {
                this.increaseBuffer(0, 1.5f);
                if (this.buffer.get(0) > 4)
                    total++;
                profile.debug("&7Aim zFactor: " + this.buffer.get(0));
                if (this.buffer.get(0) > 7.0f) {
                    this.profile.punish("Aim", "Factor", "[Statistics] Suspicious zFactor " + zFactorYaw, 0.0f);
                    this.profile.setAttackBlockToTime(System.currentTimeMillis() + 5500L);
                    this.buffer.set(0, 6.0f);
                }
            } else this.increaseBuffer(0, -1.2f);
        }
        { // total
            if (total > 0) profile.debug("&7Aim Statistics Total: " + total);
            if (total < 2.0) {
                this.increaseBuffer(10, -2f);
            } else if (total > 2.0) {
                this.increaseBuffer(10, 5f);
                if (this.buffer.get(10) >= 15) {
                    this.profile.punish("Aim", "Statistics", "[Statistics] Improbable " + this.buffer.get(10), 3.0f);
                    this.profile.setAttackBlockToTime(System.currentTimeMillis() + 3600L);
                    this.increaseBuffer(10, -2f);
                }
            }
        }
        this.rawRotations.clear();
    }
    private void increaseBuffer(int index, float v) {
        float r = this.buffer.get(index) + v;
        this.buffer.set(index, (r < 0) ? 0 : r);
    }
    private static double getDifference(double a, double b) {
        return Math.abs(Math.abs(a) - Math.abs(b));
    }
}
