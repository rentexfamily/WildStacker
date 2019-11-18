package com.bgsoftware.wildstacker.objects;

import com.bgsoftware.wildstacker.api.enums.SpawnCause;
import com.bgsoftware.wildstacker.api.enums.StackCheckResult;
import com.bgsoftware.wildstacker.api.enums.StackResult;
import com.bgsoftware.wildstacker.api.enums.UnstackResult;
import com.bgsoftware.wildstacker.api.events.DuplicateSpawnEvent;
import com.bgsoftware.wildstacker.api.events.EntityStackEvent;
import com.bgsoftware.wildstacker.api.events.EntityUnstackEvent;
import com.bgsoftware.wildstacker.api.objects.StackedEntity;
import com.bgsoftware.wildstacker.api.objects.StackedObject;
import com.bgsoftware.wildstacker.api.objects.StackedSpawner;
import com.bgsoftware.wildstacker.hooks.McMMOHook;
import com.bgsoftware.wildstacker.hooks.MythicMobsHook;
import com.bgsoftware.wildstacker.hooks.WorldGuardHook;
import com.bgsoftware.wildstacker.loot.LootTable;
import com.bgsoftware.wildstacker.loot.LootTableTemp;
import com.bgsoftware.wildstacker.utils.GeneralUtils;
import com.bgsoftware.wildstacker.utils.entity.EntityData;
import com.bgsoftware.wildstacker.utils.entity.EntityStorage;
import com.bgsoftware.wildstacker.utils.entity.EntityUtils;
import com.bgsoftware.wildstacker.utils.entity.StackCheck;
import com.bgsoftware.wildstacker.utils.items.ItemStackList;
import com.bgsoftware.wildstacker.utils.legacy.EntityTypes;
import com.bgsoftware.wildstacker.utils.particles.ParticleWrapper;
import com.bgsoftware.wildstacker.utils.threads.Executor;
import com.bgsoftware.wildstacker.utils.threads.StackService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Parrot;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class WStackedEntity extends WStackedObject<LivingEntity> implements StackedEntity {

    private static final Object stackingMutex = new Object();

    private boolean ignoreDeathEvent = false;
    private SpawnCause spawnCause;
    private com.bgsoftware.wildstacker.api.loot.LootTable tempLootTable = null;
    private EntityDamageEvent lastDamageCause;

    public WStackedEntity(LivingEntity livingEntity){
        this(livingEntity, 1, null);
    }

    public WStackedEntity(LivingEntity livingEntity, int stackAmount, @Nullable SpawnCause spawnCause){
        super(livingEntity, stackAmount);
        this.spawnCause = spawnCause;
    }

    @Override
    public Location getLocation() {
        return object.getLocation();
    }

    @Override
    public void setStackAmount(int stackAmount, boolean updateName) {
        super.setStackAmount(stackAmount, updateName);
        if(!isCached())
            plugin.getDataHandler().CACHED_AMOUNT_ENTITIES.put(object.getUniqueId(), stackAmount);
    }

    /*
     * LivingEntity's methods
     */

    @Override
    public LivingEntity getLivingEntity() {
        return object;
    }

    @Override
    public UUID getUniqueId(){
        return object.getUniqueId();
    }

    @Override
    public EntityType getType(){
        return object.getType();
    }

    @Override
    public void setHealth(double health){
        object.setHealth(health);
    }

    @Override
    public double getHealth(){
        return object.getHealth();
    }

    @Override
    public void setCustomName(String customName){
        object.setCustomName(customName);
    }

    @Override
    public void setCustomNameVisible(boolean visible){
        object.setCustomNameVisible(visible);
    }

    /*
     * StackedObject's methods
     */

    @Override
    public Chunk getChunk() {
        return object.getLocation().getChunk();
    }

    @Override
    public int getStackLimit() {
        int limit = GeneralUtils.get(plugin.getSettings().entitiesLimits, this, Integer.MAX_VALUE);
        return limit < 1 ? Integer.MAX_VALUE : limit;
    }

    @Override
    public boolean isBlacklisted() {
        return GeneralUtils.contains(plugin.getSettings().blacklistedEntities, this);
    }

    @Override
    public boolean isWhitelisted() {
        return GeneralUtils.containsOrEmpty(plugin.getSettings().whitelistedEntities, this);
    }

    @Override
    public boolean isWorldDisabled() {
        return plugin.getSettings().entitiesDisabledWorlds.contains(object.getWorld().getName());
    }

    @Override
    public void remove() {
        plugin.getSystemManager().removeStackObject(this);
        //Should be triggered synced if it's a slime
        if(EntityTypes.fromEntity(object).isSlime())
            Executor.sync(object::remove);
        else
            object.remove();
    }

    @Override
    public void updateName() {
        if(!Bukkit.isPrimaryThread()){
            Executor.sync(this::updateName);
            return;
        }

        if(EntityUtils.isNameBlacklisted(object.getCustomName()) || hasNameTag())
            return;

        if(isBlacklisted() || !isWhitelisted() || isWorldDisabled())
            return;

        try {
            String customName = EntityUtils.getEntityName(this);
            boolean nameVisible = stackAmount > 1 && !plugin.getSettings().entitiesHideNames;
            object.setCustomName(customName);
            object.setCustomNameVisible(nameVisible);

            //We update cached values of mcmmo
            if(McMMOHook.isEnabled())
                McMMOHook.updateCachedName(object);

        }catch(NullPointerException ignored){}
    }

    @Override
    public StackCheckResult runStackCheck(StackedObject stackedObject) {
        if (!plugin.getSettings().entitiesStackingEnabled)
            return StackCheckResult.NOT_ENABLED;

        StackCheckResult superResult = super.runStackCheck(stackedObject);

        if(superResult != StackCheckResult.SUCCESS)
            return superResult;

        if(isNameBlacklisted())
            return StackCheckResult.BLACKLISTED_NAME;

        if(object.isDead() || !object.isValid())
            return StackCheckResult.ALREADY_DEAD;

        if(StackCheck.NAME_TAG.isEnabled() && hasNameTag())
            return StackCheckResult.NAME_TAG;

        StackedEntity targetEntity = (StackedEntity) stackedObject;

        if(targetEntity.isNameBlacklisted())
            return StackCheckResult.TARGET_BLACKLISTD_NAME;

        if(targetEntity.getLivingEntity().isDead() || !targetEntity.getLivingEntity().isValid())
            return StackCheckResult.TARGET_ALREADY_DEAD;

        if(EntityStorage.hasMetadata(targetEntity.getLivingEntity(), "corpse"))
            return StackCheckResult.CORPSE;

        if(StackCheck.NAME_TAG.isEnabled() && targetEntity.hasNameTag())
            return StackCheckResult.TARGET_NAME_TAG;

        if(StackCheck.NERFED.isEnabled() && isNerfed() != targetEntity.isNerfed())
            return StackCheckResult.NERFED;

        if(StackCheck.SPAWN_REASON.isEnabled() && getSpawnCause() != targetEntity.getSpawnCause())
            return StackCheckResult.SPAWN_REASON;

        if (Bukkit.getPluginManager().isPluginEnabled("WorldGuard")){
            Set<String> regions = new HashSet<>();
            regions.addAll(WorldGuardHook.getRegionsName(targetEntity.getLivingEntity().getLocation()));
            regions.addAll(WorldGuardHook.getRegionsName(object.getLocation()));
            if(regions.stream().anyMatch(region -> plugin.getSettings().entitiesDisabledRegions.contains(region)))
                return StackCheckResult.DISABLED_REGION;
        }

        if (plugin.getSettings().stackDownEnabled && GeneralUtils.contains(plugin.getSettings().stackDownTypes, this)) {
            if (object.getLocation().getY() < targetEntity.getLivingEntity().getLocation().getY()) {
                targetEntity.runStackAsync(this, null);
                return StackCheckResult.NOT_BELOW;
            }
        }

        return StackCheckResult.SUCCESS;
    }

    @Override
    public void runStackAsync(Consumer<Optional<LivingEntity>> result) {
        if(!Bukkit.isPrimaryThread()){
            Executor.sync(() -> runStackAsync(result));
            return;
        }

        int range = plugin.getSettings().entitiesCheckRange;

        List<Entity> nearbyEntities = plugin.getNMSAdapter().getNearbyEntities(object, range,
                entity -> entity instanceof LivingEntity && !(entity instanceof ArmorStand) && !(entity instanceof Player));

        //Cache data of entities
        EntityData.cache(object);
        nearbyEntities.forEach(entity -> EntityData.cache((LivingEntity) entity));

        StackService.execute(() -> {
            synchronized (stackingMutex) {
                int minimumStackSize = plugin.getSettings().minimumEntitiesLimit.getOrDefault(getType().name(), 1);
                Location entityLocation = getLivingEntity().getLocation();

                Set<StackedEntity> filteredEntities = nearbyEntities.stream().map(WStackedEntity::of)
                        .filter(stackedEntity -> runStackCheck(stackedEntity) == StackCheckResult.SUCCESS)
                        .collect(Collectors.toSet());
                Optional<StackedEntity> entityOptional = filteredEntities.stream()
                        .min(Comparator.comparingDouble(o -> o.getLivingEntity().getLocation().distanceSquared(entityLocation)));

                if (entityOptional.isPresent()) {
                    StackedEntity targetEntity = entityOptional.get();

                    if (minimumStackSize > 2) {
                        int totalStackSize = getStackAmount();

                        for (StackedEntity stackedEntity : filteredEntities)
                            totalStackSize += stackedEntity.getStackAmount();

                        if (totalStackSize < minimumStackSize) {
                            updateName();
                            if (result != null)
                                result.accept(Optional.empty());
                            return;
                        }

                        filteredEntities.forEach(nearbyEntity -> nearbyEntity.runStackAsync(targetEntity, null));
                    }

                    StackResult stackResult = runStack(targetEntity);

                    if (stackResult != StackResult.SUCCESS) {
                        updateName();
                        if (result != null)
                            result.accept(Optional.empty());
                        return;
                    }

                }

                if (result != null)
                    result.accept(entityOptional.map(StackedEntity::getLivingEntity));
            }
        });
    }

    @Override
    public StackResult runStack(StackedObject stackedObject) {
        synchronized (stackingMutex) {
            if (!StackService.canStackFromThread())
                return StackResult.THREAD_CATCHER;

            if (runStackCheck(stackedObject) != StackCheckResult.SUCCESS)
                return StackResult.NOT_SIMILAR;

            StackedEntity targetEntity = (StackedEntity) stackedObject;

            EntityStackEvent entityStackEvent = new EntityStackEvent(targetEntity, this);
            Bukkit.getPluginManager().callEvent(entityStackEvent);

            if (entityStackEvent.isCancelled())
                return StackResult.EVENT_CANCELLED;

            double health = GeneralUtils.contains(plugin.getSettings().keepLowestHealth, this) ?
                    Math.min(getHealth(), targetEntity.getHealth()) : targetEntity.getHealth();
            int newStackAmount = getStackAmount() + targetEntity.getStackAmount();

            targetEntity.setStackAmount(newStackAmount, false);
            targetEntity.setHealth(health);

            Executor.sync(() -> {
                if (targetEntity.getLivingEntity().isValid())
                    targetEntity.updateName();
            }, 2L);

            plugin.getSystemManager().updateLinkedEntity(object, targetEntity.getLivingEntity());

            if (object.getType().name().equals("PARROT"))
                Executor.sync(() -> EntityUtils.removeParrotIfShoulder((Parrot) object));

            this.remove();

            if (plugin.getSettings().entitiesParticlesEnabled) {
                Location location = getLivingEntity().getLocation();
                for (ParticleWrapper particleWrapper : plugin.getSettings().entitiesParticles)
                    particleWrapper.spawnParticle(location);
            }

            return StackResult.SUCCESS;
        }
    }

    @Override
    public UnstackResult runUnstack(int amount) {
        EntityUnstackEvent entityUnstackEvent = new EntityUnstackEvent(this, amount);
        Bukkit.getPluginManager().callEvent(entityUnstackEvent);

        if(entityUnstackEvent.isCancelled())
            return UnstackResult.EVENT_CANCELLED;

        int stackAmount = this.stackAmount - amount;

        setStackAmount(stackAmount, true);

        if(stackAmount >= 1){
            spawnCorpse();
        }

        else {
            Executor.sync(() -> {
                EntityStorage.setMetadata(object, "corpse", null);
                plugin.getNMSAdapter().setHealthDirectly(object, 0);
                plugin.getNMSAdapter().playDeathSound(object);
            }, 2L);
        }

        return UnstackResult.SUCCESS;
    }

    @Override
    public boolean isSimilar(StackedObject stackedObject) {
        return stackedObject instanceof StackedEntity && EntityUtils.areEquals(this, (StackedEntity) stackedObject);
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof StackedEntity ? getUniqueId().equals(((StackedEntity) obj).getUniqueId()) : super.equals(obj);
    }

    @Override
    public String toString() {
        return String.format("StackedEntity{uuid=%s,amount=%s,type=%s}", getUniqueId(), getStackAmount(), getType().name());
    }

    /*
     * StackedEntity's methods
     */

    @Override
    public synchronized void runSpawnerStackAsync(StackedSpawner stackedSpawner, Consumer<Optional<LivingEntity>> result) {
        if (!plugin.getSettings().linkedEntitiesEnabled) {
            runStackAsync(result);
            return;
        }

        StackService.execute(() -> {
            LivingEntity linkedEntity = stackedSpawner.getLinkedEntity();

            if (linkedEntity != null) {
                StackedEntity targetEntity = WStackedEntity.of(linkedEntity);

                StackResult stackResult = runStack(targetEntity);

                if (stackResult == StackResult.SUCCESS) {
                    if (result != null)
                        result.accept(Optional.of(linkedEntity));
                    return;
                }
//                try {
//                    boolean cont = StackService.submit(targetEntity, () -> {
//                        StackResult stackResult = runStack(targetEntity);
//
//                        if (stackResult == StackResult.SUCCESS) {
//                            if (result != null)
//                                result.accept(Optional.of(linkedEntity));
//                            return false;
//                        }
//
//                        return true;
//                    }).get();
//
//                    if (!cont)
//                        return;
//                } catch (Exception ignored) {
//                }
            }

            runStackAsync(entityOptional -> {
                LivingEntity targetEntity = entityOptional.orElse(object);
                stackedSpawner.setLinkedEntity(targetEntity);
                if (result != null)
                    result.accept(Optional.of(targetEntity));
            });
        });
    }

    @Override
    public LivingEntity trySpawnerStack(StackedSpawner stackedSpawner){
        new UnsupportedOperationException("trySpawnerStack method is no longer supported.").printStackTrace();
        runSpawnerStackAsync(stackedSpawner, null);
        return null;
    }

    @Override
    public StackedEntity spawnDuplicate(int amount) {
        if (amount <= 0)
            return null;

        LivingEntity _duplicate;

        if(getSpawnCause() == SpawnCause.MYTHIC_MOBS && (_duplicate = MythicMobsHook.tryDuplicate(object)) != null) {
            StackedEntity duplicate = WStackedEntity.of(_duplicate);
            duplicate.setStackAmount(amount, true);
            return duplicate;
        }

        StackedEntity duplicate = WStackedEntity.of(plugin.getSystemManager().spawnEntityWithoutStacking(object.getLocation(), getType().getEntityClass(), getSpawnCause()));
        duplicate.setStackAmount(amount, true);

        EntityData entityData = EntityData.of(this);
        entityData.applyEntityData(duplicate.getLivingEntity());
        //EntityUtil.applyEntityData(object, duplicate.getLivingEntity());

        if(plugin.getSettings().keepFireEnabled && object.getFireTicks() > -1)
            duplicate.getLivingEntity().setFireTicks(160);

        DuplicateSpawnEvent duplicateSpawnEvent = new DuplicateSpawnEvent(this, duplicate);
        Bukkit.getPluginManager().callEvent(duplicateSpawnEvent);

        return duplicate;
    }

    @Override
    public void spawnCorpse() {
        if(!Bukkit.isPrimaryThread()){
            Executor.sync(this::spawnCorpse);
            return;
        }

        plugin.getSystemManager().spawnCorpse(this);
    }

    @Override
    public List<ItemStack> getDrops(int lootBonusLevel) {
        return getDrops(lootBonusLevel, stackAmount);
    }

    @Override
    public List<ItemStack> getDrops(int lootBonusLevel, int stackAmount) {
        ItemStackList drops = new ItemStackList();

        if(lastDamageCause != null)
            object.setLastDamageCause(lastDamageCause);

        if(tempLootTable != null){
            drops.addAll(tempLootTable.getDrops(this, lootBonusLevel, stackAmount));
            tempLootTable = null;
        }

        else{
            LootTable lootTable = plugin.getLootHandler().getLootTable(object);
            drops.addAll(lootTable.getDrops(this, lootBonusLevel, stackAmount));
        }

        return drops.toList();
    }

    @Override
    public void setDrops(List<ItemStack> itemStacks) {
        this.tempLootTable = new LootTableTemp() {
            @Override
            public List<ItemStack> getDrops(StackedEntity stackedEntity, int lootBonusLevel, int stackAmount) {
                List<ItemStack> drops = new ArrayList<>();

                itemStacks.stream()
                        .filter(itemStack -> itemStack != null && itemStack.getType() != Material.AIR)
                        .forEach(itemStack -> {
                            ItemStack cloned = itemStack.clone();
                            cloned.setAmount(itemStack.getAmount() * stackAmount);
                            drops.add(cloned);
                        });

                return drops;
            }
        };
    }

    @Override
    @Deprecated
    public void setTempLootTable(List<ItemStack> itemStacks) {
        setDrops(itemStacks);
    }

    @Override
    public void setDropsMultiplier(int multiplier) {
        this.tempLootTable = new LootTableTemp() {
            @Override
            public List<ItemStack> getDrops(StackedEntity stackedEntity, int lootBonusLevel, int stackAmount) {
                tempLootTable = null;
                List<ItemStack> drops = stackedEntity.getDrops(lootBonusLevel, stackAmount);
                drops.forEach(itemStack -> itemStack.setAmount((itemStack.getAmount() * multiplier)));
                return drops;
            }
        };
    }

    @Override
    @Deprecated
    public void setLootMultiplier(int multiplier) {
        setDropsMultiplier(multiplier);
    }

    @Override
    public int getExp(int stackAmount, int defaultExp) {
        return plugin.getLootHandler().getLootTable(object).getExp(this, stackAmount);
    }

    @Override
    @Deprecated
    public void ignoreDeathEvent() {
        ignoreDeathEvent = true;
    }

    @Override
    @Deprecated
    public boolean isIgnoreDeathEvent() {
        return ignoreDeathEvent;
    }

    @Override
    public SpawnCause getSpawnCause() {
        return spawnCause == null ? SpawnCause.CHUNK_GEN : spawnCause;
    }

    @Override
    public void setSpawnCause(SpawnCause spawnCause) {
        this.spawnCause = spawnCause == null ? SpawnCause.CHUNK_GEN : spawnCause;
        if(!isCached())
            plugin.getDataHandler().CACHED_SPAWN_CAUSE_ENTITIES.put(object.getUniqueId(), this.spawnCause);
    }

    @Override
    public boolean isNerfed(){
        return GeneralUtils.containsOrEmpty(plugin.getSettings().entitiesNerfedWhitelist, this) &&
                !GeneralUtils.contains(plugin.getSettings().entitiesNerfedBlacklist, this) &&
                (plugin.getSettings().entitiesNerfedWorlds.isEmpty() || plugin.getSettings().entitiesNerfedWorlds.contains(object.getWorld().getName()));
    }

    @Override
    public void setNerfed(boolean nerfed) {
        plugin.getNMSAdapter().setNerfedEntity(object, nerfed);
    }

    @Override
    public void updateNerfed() {
        setNerfed(isNerfed());
    }

    @Override
    public boolean isNameBlacklisted() {
        return EntityUtils.isNameBlacklisted(object.getCustomName());
    }

    @Override
    public boolean isInstantKill(EntityDamageEvent.DamageCause damageCause) {
        return GeneralUtils.contains(plugin.getSettings().entitiesInstantKills, this) ||
                (damageCause != null && (plugin.getSettings().entitiesInstantKills.contains(damageCause.name()) ||
                        plugin.getSettings().entitiesInstantKills.contains(getType().name() + ":" + damageCause.name()))) ||
                getLivingEntity().getHealth() <= 0;
    }

    @Override
    public int getDefaultUnstack() {
        return GeneralUtils.get(plugin.getSettings().defaultUnstack, this, 1);
    }

    @Override
    public boolean hasNameTag() {
        String regexName = ChatColor.stripColor(object.getCustomName());

        try{
            regexName = EntityUtils.getEntityNameRegex(this);
        }catch(NullPointerException ignored){}

        return object.getCustomName() != null && !object.isCustomNameVisible() && (regexName.isEmpty() || !Pattern.compile(regexName).matcher(object.getCustomName()).matches());
    }

    public boolean isCached(){
        return plugin.getSettings().entitiesStackingEnabled && isWhitelisted() && !isBlacklisted() && !isWorldDisabled();
    }

    public void setLastDamageCause(EntityDamageEvent lastDamageCause){
        this.lastDamageCause = lastDamageCause;
    }

    public static StackedEntity of(Entity entity){
        if(EntityUtils.isStackable(entity))
            return of((LivingEntity) entity);
        throw new IllegalArgumentException("The entity-type " + entity.getType() + " is not a stackable entity.");
    }

    public static StackedEntity of(LivingEntity livingEntity){
        return plugin.getSystemManager().getStackedEntity(livingEntity);
    }
}
