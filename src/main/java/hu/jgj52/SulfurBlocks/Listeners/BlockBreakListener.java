package hu.jgj52.SulfurBlocks.Listeners;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import hu.jgj52.Sulfur.Sulfur;
import hu.jgj52.Sulfur.Utils.Listeners.Event;
import hu.jgj52.Sulfur.Utils.Listeners.Listener;
import hu.jgj52.SulfurBlocks.SulfurBlocks;
import net.kyori.adventure.key.Key;
import net.minestom.server.MinecraftServer;
import net.minestom.server.component.DataComponents;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.*;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.component.EnchantmentList;
import net.minestom.server.item.component.Tool;
import net.minestom.server.item.enchant.Enchantment;
import net.minestom.server.network.packet.server.play.BlockBreakAnimationPacket;
import net.minestom.server.potion.PotionEffect;
import net.minestom.server.potion.TimedPotion;
import net.minestom.server.timer.Scheduler;
import net.minestom.server.timer.Task;
import net.minestom.server.timer.TaskSchedule;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class BlockBreakListener extends Listener {
    @Event
    public void onBreak(PlayerBlockBreakEvent event) {
        JsonObject block = SulfurBlocks.blocks.get(event.getBlock().id());
        if (block == null) return;

        Instance instance = event.getInstance();
        BlockVec pos = event.getBlockPosition();

        Tool tool = event.getPlayer().getItemInMainHand().get(DataComponents.TOOL);
        boolean creative = event.getPlayer().getGameMode() == GameMode.CREATIVE;
        if (tool != null && !tool.canDestroyBlocksInCreative() && creative) {
            event.setCancelled(true);
            return;
        }
        sendBreakAnimation(instance, pos, event.getPlayer().getEntityId(), 10);
        if (creative) return; //TODO: ores, check if block actually broke
        if (!event.getBlock().registry().requiresTool() || (tool != null && tool.isCorrectForDrops(event.getBlock()))) {
            Random r = new Random();
            ItemEntity itemEntity = new ItemEntity(getDrops(block, event.getPlayer().getItemInMainHand()));
            itemEntity.setInstance(instance, pos.add(0.5d, 0.25d, 0.5d));
            itemEntity.setVelocity(itemEntity.getVelocity().add((r.nextFloat(2)) * 3 - 3, 4, (r.nextFloat(2) * 3) - 3));
            itemEntity.setPickupDelay(Duration.ofMillis(500));
        }
    }

    private void sendBreakAnimation(Instance instance, BlockVec pos, int entityId, int state) {
        BlockBreakAnimationPacket packet = new BlockBreakAnimationPacket(
                entityId,
                pos,
                (byte) state
        );
        for (Player player : instance.getPlayers()) {
            if (player.getEntityId() == entityId) continue;
            double dx = player.getPosition().x() - pos.x();
            double dy = player.getPosition().y() - pos.y();
            double dz = player.getPosition().z() - pos.z();

            int renderDistance = Sulfur.conf.get("server").getAsJsonObject().get("renderDistance").getAsInt() * 16;

            if ((dx*dx + dy*dy + dz*dz) <= renderDistance * renderDistance) {
                player.sendPacket(packet);
            }
        }
    }

    private ItemStack getDrops(JsonObject lootTable, ItemStack heldItem) {
        if (!lootTable.has("pools")) return ItemStack.AIR;

        for (JsonElement poolEl : lootTable.getAsJsonArray("pools")) {
            JsonObject pool = poolEl.getAsJsonObject();

            for (JsonElement entryEl : pool.getAsJsonArray("entries")) {
                JsonObject entry = entryEl.getAsJsonObject();
                String entryType = entry.get("type").getAsString();

                if (entryType.equals("minecraft:alternatives")) {
                    for (JsonElement childEl : entry.getAsJsonArray("children")) {
                        JsonObject child = childEl.getAsJsonObject();

                        if (checkConditions(child, heldItem)) {
                            String itemName = child.get("name").getAsString();
                            Material material = Material.fromKey(itemName);
                            if (material != null) return ItemStack.of(material);
                        }
                    }
                } else if (entryType.equals("minecraft:item")) {
                    if (checkConditions(entry, heldItem)) {
                        String itemName = entry.get("name").getAsString();
                        Material material = Material.fromKey(itemName);
                        if (material != null) return ItemStack.of(material);
                    }
                }
            }
        }

        return ItemStack.AIR;
    }

    private boolean checkConditions(JsonObject entry, ItemStack heldItem) {
        if (!entry.has("conditions")) return true;

        for (JsonElement condEl : entry.getAsJsonArray("conditions")) {
            JsonObject cond = condEl.getAsJsonObject();
            String condition = cond.get("condition").getAsString();

            switch (condition) {
                case "minecraft:match_tool" -> {
                    JsonObject predicate = cond.getAsJsonObject("predicate");
                    if (!matchTool(predicate, heldItem)) return false;
                }
            }
        }
        return true;
    }

    private boolean matchTool(JsonObject predicate, ItemStack heldItem) {
        if (!predicate.has("predicates")) return true;
        JsonObject predicates = predicate.getAsJsonObject("predicates");

        if (predicates.has("minecraft:enchantments")) {
            for (JsonElement enchEl : predicates.getAsJsonArray("minecraft:enchantments")) {
                JsonObject ench = enchEl.getAsJsonObject();
                String enchId = ench.get("enchantments").getAsString();
                int minLevel = ench.getAsJsonObject("levels").get("min").getAsInt();

                EnchantmentList enchantments = heldItem.get(DataComponents.ENCHANTMENTS);
                if (enchantments == null) return false;

                Integer level = enchantments.enchantments().entrySet().stream()
                        .filter(e -> Objects.equals(e.getKey(), MinecraftServer.process().enchantment().getKey(Key.key(enchId))))
                        .map(Map.Entry::getValue)
                        .findFirst()
                        .orElse(null);

                if (level == null || level < minLevel) return false;
            }
        }

        return true;
    }

    private final Scheduler scheduler = MinecraftServer.getSchedulerManager();
    private final Map<UUID, Task> breakingTasks = new ConcurrentHashMap<>();

    @Event
    public void onStartBreaking(PlayerStartDiggingEvent event) {
        Block block = event.getBlock();
        Player player = event.getPlayer();

        float hardness = block.registry().hardness();
        float speed = 1f;

        Tool tool = player.getItemInMainHand().get(DataComponents.TOOL);
        boolean correctTool = tool != null && tool.isCorrectForDrops(block);
        boolean requiresTool = block.registry().requiresTool();

        if (tool != null) {
            speed = (!requiresTool || correctTool) ? tool.getSpeed(block) : 1f;
        }

        TimedPotion haste = getEffect(player, PotionEffect.HASTE);
        TimedPotion fatigue = getEffect(player, PotionEffect.MINING_FATIGUE);

        if (haste != null)   speed *= 1f + 0.2f * (haste.potion().amplifier() + 1);
        if (fatigue != null) speed *= (float) Math.pow(0.3, Math.min(fatigue.potion().amplifier() + 1, 4));

        if (!player.isOnGround()) speed /= 5f;

        player.getHelmet();
        EnchantmentList enchantments = player.getHelmet().get(DataComponents.ENCHANTMENTS);
        boolean hasAquaAffinity = enchantments != null && enchantments.has(Enchantment.AQUA_AFFINITY);

        if (event.getInstance().getBlock(player.getPosition().add(0, 1, 0)).registry().isLiquid() && !hasAquaAffinity) speed /= 5f;

        float f;
        if (!requiresTool || correctTool) {
            f = 30;
        } else {
            f = 100;
        }

        float ticksToBreak = hardness * f / speed;

        long intervalMillis = (long) ((ticksToBreak * 50f) / 10f);
        AtomicInteger state = new AtomicInteger(-1);

        Task task = scheduler.submitTask(() -> {
            int s = state.incrementAndGet();
            sendBreakAnimation(event.getInstance(), event.getBlockPosition(), event.getPlayer().getEntityId(), s);
            if (s >= 9) return TaskSchedule.stop();
            return TaskSchedule.millis(intervalMillis);
        });
        breakingTasks.put(event.getPlayer().getUuid(), task);
    }

    private TimedPotion getEffect(Player player, PotionEffect effect) {
        return player.getActiveEffects().stream()
                .filter(p -> p.potion().effect() == effect)
                .findFirst().orElse(null);
    }

    @Event
    public void onCancelBreaking(PlayerCancelDiggingEvent event) {
        Task task = breakingTasks.remove(event.getPlayer().getUuid());
        if (task != null) task.cancel();
        sendBreakAnimation(event.getInstance(), event.getBlockPosition(), event.getPlayer().getEntityId(), -1);
    }

    @Event
    public void onPlace(PlayerBlockPlaceEvent event) {
        sendBreakAnimation(event.getInstance(), event.getBlockPosition(), event.getPlayer().getEntityId(), -1);
    }
}

