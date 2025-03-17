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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class AimAnalysisCheck implements PacketCheckHandler {
    private final List<Float> buffer;
    private final PlayerProfile profile;
    private final List<Vec2f> rawRotations;
    private long lastAttack;
    public AimAnalysisCheck(PlayerProfile profile) {
        this.profile = profile;
        this.rawRotations = new CopyOnWriteArrayList<>();
        this.lastAttack = 0L;
        this.buffer = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 16; i++) this.buffer.add(0.0f);
    }
    @Override
    public void event(Object o) {
        if (o instanceof RotationEvent) {
            RotationEvent event = (RotationEvent) o;
            if (System.currentTimeMillis() > this.lastAttack + 3500) return;
            Vec2f delta = event.getDelta();
            this.rawRotations.add(delta);
            if (this.rawRotations.size() >= 100) this.checkRaw();
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
            final List<Float> x = new ArrayList<>(), xAbs = new ArrayList<>(), y = new ArrayList<>();
            for (Vec2f vec2 : this.rawRotations) {
                x.add(vec2.getX());
                xAbs.add(vec2.getX());
                y.add(vec2.getY());
            }
            double distinctX = Statistics.getDistinct(x);
            double max = Math.abs(Statistics.getMax(xAbs));
            double kurtosis = Statistics.getKurtosis(x);
            double pearson = Statistics.getPearsonCorrelation(x, y);
            final int spikes = Statistics.getZScoreOutliers(x, 1.0f).size() + Statistics.getZScoreOutliers(y, 1.0f).size();
            if (max > 8 && pearson < 0.25 && distinctX < 85 && distinctX > 40 && kurtosis > 0 && spikes >= 40) {
                this.increaseBuffer(0, (distinctX < 80) ? 1.25f : 0.85f);
                profile.debug("&7Aim Incorrect distribution: " + this.buffer.get(0));
                if (this.buffer.get(0) > 2.1f) {
                    this.profile.punish("Aim", "Distribution", "[Analysis] Incorrect distribution [" + distinctX + ", " + pearson + ", " + max + "]", 2.0f);
                    this.buffer.set(0, 1.5f);
                }
            } else this.increaseBuffer(0, -0.4f);
            //profile.getPlayer().sendMessage("f: " + distinctX + " " + pearson + " " + max + " " + kurtosis + " " + spikes);
        }
        this.rawRotations.clear();
    }

    private void increaseBuffer(int index, float v) {
        float r = this.buffer.get(index) + v;
        this.buffer.set(index, (r < 0) ? 0 : r);
    }
    private void mulBuffer(int index, float v) {
        float r = this.buffer.get(index) * v;
        this.buffer.set(index, (r < 0) ? 0 : r);
    }
}
