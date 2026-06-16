package dev.cxd.owl_delivery.entity;

import dev.cxd.owl_delivery.entity.goals.FlyTreeGoal;
import dev.cxd.owl_delivery.entity.goals.OwlDeliverBundleGoal;
import dev.cxd.owl_delivery.init.ModEntities;
import dev.cxd.owl_delivery.init.ModSounds;
import dev.cxd.owl_delivery.init.ModTags;
import net.minecraft.entity.AnimationState;
import net.minecraft.entity.EntityData;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.ai.control.FlightMoveControl;
import net.minecraft.entity.ai.goal.*;
import net.minecraft.entity.ai.pathing.BirdNavigation;
import net.minecraft.entity.ai.pathing.EntityNavigation;
import net.minecraft.entity.ai.pathing.PathNodeType;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.Angerable;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.recipe.Ingredient;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.BiomeTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Util;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.EntityView;
import net.minecraft.world.LocalDifficulty;
import net.minecraft.world.ServerWorldAccess;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class OwlEntity extends TameableEntity implements Angerable {

    private static final TrackedData<Boolean> SITTING =
            DataTracker.registerData(OwlEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Optional<UUID>> RECEIVER_UUID =
            DataTracker.registerData(OwlEntity.class, TrackedDataHandlerRegistry.OPTIONAL_UUID);
    public static final TrackedData<Boolean> GOING_TO_RECEIVER =
            DataTracker.registerData(OwlEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Integer> DATA_ID_TYPE_VARIANT =
            DataTracker.registerData(OwlEntity.class, TrackedDataHandlerRegistry.INTEGER);

    public final AnimationState idleAnimationState = new AnimationState();
    public final AnimationState sittingAnimationState = new AnimationState();
    private int idleAnimationTimeout = 0;

    private ItemStack carriedBundle = ItemStack.EMPTY;
    private int previousVariantId = -1;

    public OwlEntity(EntityType<? extends TameableEntity> entityType, World world) {
        super(entityType, world);
        this.moveControl = new FlightMoveControl(this, 10, false);
        this.setPathfindingPenalty(PathNodeType.DANGER_FIRE, -1.0F);
        this.setPathfindingPenalty(PathNodeType.DAMAGE_FIRE, -1.0F);
        this.setPathfindingPenalty(PathNodeType.COCOA, -1.0F);
    }

    @Override
    protected void initGoals() {
        this.goalSelector.add(0, new SwimGoal(this));
        this.goalSelector.add(0, new OwlDeliverBundleGoal<>(this, 1.0D, 6.0F, 128.0F, false));
        this.goalSelector.add(1, new AnimalMateGoal(this, 1.0F));
        this.goalSelector.add(1, new LookAtEntityGoal(this, PlayerEntity.class, 8.0F));
        this.goalSelector.add(2, new SitGoal(this));
        this.goalSelector.add(4, new TemptGoal(this, 1.25D, Ingredient.ofItems(Items.RABBIT_FOOT, Items.RABBIT), false));
        this.goalSelector.add(4, new FollowOwnerGoal(this, 1.0F, 5.0F, 1.0F, true));
        this.goalSelector.add(4, new FlyTreeGoal(this, 1.0F));
        this.goalSelector.add(4, new PounceAtTargetGoal(this, 0.4F));
        this.goalSelector.add(5, new MeleeAttackGoal(this, 1.0F, true));
        this.goalSelector.add(6, new FollowMobGoal(this, 1.0F, 3.0F, 7.0F));

        this.targetSelector.add(1, new ActiveTargetGoal<>(this, net.minecraft.entity.passive.RabbitEntity.class, true) {
            @Override
            public boolean canStart() {
                return !OwlEntity.this.isTamed() && super.canStart();
            }
            @Override
            public boolean shouldContinue() {
                return !OwlEntity.this.isTamed() && super.shouldContinue();
            }
        });
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return TameableEntity.createMobAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 10)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 2)
                .add(EntityAttributes.GENERIC_FLYING_SPEED, 0.6F)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.2F)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 32);
    }

    private static final Map<String, OwlVariant> NAME_TAG_VARIANTS = Map.of(
            "folly",     OwlVariant.FOLLY_OWL,
            "lux",       OwlVariant.LUX_OWL,
            "luxintrus", OwlVariant.LUX_OWL,
            "nycto",     OwlVariant.VAMPIRE_OWL,
            "vampire",   OwlVariant.VAMPIRE_OWL
    );

    @Override
    public void setCustomName(@Nullable Text name) {
        super.setCustomName(name);
        if (this.getWorld().isClient()) return;

        if (name != null) {
            OwlVariant target = NAME_TAG_VARIANTS.get(name.getString().toLowerCase());
            if (target != null) {
                if (!isNameTagVariant(getVariant())) {
                    previousVariantId = getTypeVariant();
                }
                setVariant(target);
            }
        } else {
            if (isNameTagVariant(getVariant()) && previousVariantId >= 0) {
                dataTracker.set(DATA_ID_TYPE_VARIANT, previousVariantId);
                previousVariantId = -1;
            }
        }
    }

    private boolean isNameTagVariant(OwlVariant variant) {
        return variant == OwlVariant.FOLLY_OWL
                || variant == OwlVariant.LUX_OWL
                || variant == OwlVariant.VAMPIRE_OWL;
    }

    @Override
    public ActionResult interactMob(PlayerEntity player, Hand hand) {
        ItemStack stack = player.getStackInHand(hand);

        if (!this.isTamed() && stack.isOf(Items.RABBIT)) {
            this.eat(player, hand, stack);
            if (!this.getWorld().isClient) {
                if (this.random.nextInt(5) == 0) {
                    this.setOwner(player);
                    this.setSitting(true);
                    this.getWorld().sendEntityStatus(this, (byte) 7);
                } else {
                    this.getWorld().sendEntityStatus(this, (byte) 6);
                }
            }
            return ActionResult.SUCCESS;
        }

        if (this.isTamed() && this.isOwner(player) && stack.isIn(ModTags.Items.BUNDLE) && !stack.getName().getString().isEmpty()) {
            String targetName = stack.getName().getString();
            MinecraftServer server = this.getServer();
            if (server != null) {
                ServerPlayerEntity target = server.getPlayerManager().getPlayerList().stream()
                        .filter(p -> p.getName().getString().equalsIgnoreCase(targetName))
                        .findFirst().orElse(null);
                if (target != null) {
                    this.setReceiverUuid(target.getUuid());
                    this.setCarriedBundle(stack.copy());
                    stack.decrement(1);
                    this.setSitting(false);
                    return ActionResult.SUCCESS;
                } else {
                    player.sendMessage(Text.literal("No player named \"" + targetName + "\" is online."), true);
                    return ActionResult.FAIL;
                }
            }
        }

        if (this.isTamed() && this.isOwner(player) && stack.isOf(Items.RABBIT_FOOT)) {
            if (!this.getWorld().isClient) {
                this.setSitting(false);
            }
            return super.interactMob(player, hand);
        }

        if (this.isTamed() && this.isOwner(player)) {
            if (!this.getWorld().isClient) {
                this.setSitting(!this.isSitting());
                this.navigation.stop();
                this.setTarget(null);
            }
            return ActionResult.SUCCESS;
        }

        return super.interactMob(player, hand);
    }

    @Override
    protected void dropLoot(DamageSource source, boolean causedByPlayer) {
        super.dropLoot(source, causedByPlayer);
        if (!this.carriedBundle.isEmpty()) {
            this.dropStack(this.carriedBundle);
            this.carriedBundle = ItemStack.EMPTY;
        }
    }

    public void setCarriedBundle(ItemStack stack) { this.carriedBundle = stack; }
    public ItemStack getCarriedBundle() { return this.carriedBundle; }
    public boolean hasCarriedBundle() { return !this.carriedBundle.isEmpty(); }

    public void setReceiverUuid(@Nullable UUID uuid) {
        this.dataTracker.set(RECEIVER_UUID, Optional.ofNullable(uuid));
    }

    public UUID getReceiverUuid() {
        return this.dataTracker.get(RECEIVER_UUID).orElse(null);
    }

    public boolean isGoingToReceiver() {return this.dataTracker.get(GOING_TO_RECEIVER);}

    @Override
    protected EntityNavigation createNavigation(World world) {
        BirdNavigation nav = new BirdNavigation(this, world);
        nav.setCanSwim(true);
        return nav;
    }

    @Override
    public boolean handleFallDamage(float fallDistance, float damageMultiplier, DamageSource damageSource) {
        return false;
    }

    @Override
    public void tickMovement() {
        Vec3d vec = this.getVelocity();
        if (!this.isOnGround() && vec.y < 0.0D) {
            this.setVelocity(vec.multiply(1.0D, 0.6D, 1.0D));
        }
        super.tickMovement();
    }

    @Override
    public void tick() {
        super.tick();
        if (this.getWorld().isClient()) {
            setupAnimationStates();
        }
    }

    @Override
    public boolean isBreedingItem(ItemStack stack) {return stack.isOf(Items.RABBIT_FOOT);}

    @Override
    public boolean canBreedWith(AnimalEntity other) {
        if (other == this) return false;
        if (!(other instanceof OwlEntity owl)) return false;
        if (!this.isTamed() || !owl.isTamed()) return false;
        return this.isInLove() && owl.isInLove();
    }

    @Nullable
    @Override
    public PassiveEntity createChild(ServerWorld world, PassiveEntity entity) {
        OwlEntity baby = ModEntities.OWL.create(world);
        assert baby != null;

        if (entity instanceof OwlEntity other) {
            OwlVariant chosenVariant;
            if (this.random.nextInt(200) == 0) {
                chosenVariant = OwlVariant.SHINY_OWL;
            } else {
                OwlVariant[] parentVariants = new OwlVariant[]{
                        getNaturalVariant(this),
                        getNaturalVariant(other)
                };
                chosenVariant = Util.getRandom(Arrays.asList(parentVariants), this.random);
            }
            baby.setVariant(chosenVariant);

            if (this.isTamed() && other.isTamed()) {
                baby.setTamed(true);
                baby.setOwner((PlayerEntity) this.getOwner());
            }
        }

        return baby;
    }

    private OwlVariant getNaturalVariant(OwlEntity owl) {
        if (owl.isNameTagVariant(owl.getVariant()) && owl.previousVariantId >= 0) {
            return OwlVariant.byId(owl.previousVariantId);
        }
        return owl.getVariant();
    }

    @Override
    protected void initDataTracker() {
        super.initDataTracker();
        this.dataTracker.startTracking(RECEIVER_UUID, Optional.empty());
        this.dataTracker.startTracking(GOING_TO_RECEIVER, false);
        this.dataTracker.startTracking(DATA_ID_TYPE_VARIANT, 0);
        this.dataTracker.startTracking(SITTING, false);
    }

    @Override
    public boolean isSitting() { return this.dataTracker.get(SITTING); }

    @Override
    public void setSitting(boolean sitting) { this.dataTracker.set(SITTING, sitting); }

    public OwlVariant getVariant() { return OwlVariant.byId(this.getTypeVariant() & 255); }

    private int getTypeVariant() { return this.dataTracker.get(DATA_ID_TYPE_VARIANT); }

    public void setVariant(OwlVariant variant) {
        this.dataTracker.set(DATA_ID_TYPE_VARIANT, variant.getId() & 255);
    }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putBoolean("Sitting", this.isSitting());
        nbt.putInt("Variant", this.getTypeVariant());
        nbt.putInt("PreviousVariant", this.previousVariantId);
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        this.setSitting(nbt.getBoolean("Sitting"));
        this.dataTracker.set(DATA_ID_TYPE_VARIANT, nbt.getInt("Variant"));
        this.previousVariantId = nbt.contains("PreviousVariant") ? nbt.getInt("PreviousVariant") : -1;
    }

    @Override
    public EntityData initialize(ServerWorldAccess world, LocalDifficulty difficulty, SpawnReason spawnReason,
                                 @Nullable EntityData entityData, @Nullable NbtCompound entityNbt) {
        setVariant(pickSpawnVariant(world));
        return super.initialize(world, difficulty, spawnReason, entityData, entityNbt);
    }

    private OwlVariant pickSpawnVariant(ServerWorldAccess world) {
        if (this.random.nextInt(200) == 0) {
            return OwlVariant.SHINY_OWL;
        }

        RegistryEntry<Biome> biome = world.getBiome(this.getBlockPos());

        if (biome.matchesKey(BiomeKeys.SNOWY_TAIGA)) {
            return switch (this.random.nextInt(3)) {
                case 0  -> OwlVariant.SNOW_OWL_BLUE_EYED;
                case 1  -> OwlVariant.SNOW_OWL_GREEN_EYED;
                default -> OwlVariant.SNOW_OWL;
            };
        }

        if (biome.matchesKey(BiomeKeys.TAIGA)) {
            return this.random.nextBoolean() ? OwlVariant.BLACK_OWL : OwlVariant.BLACK_OWL_GREEN_EYED;
        }

        if (biome.isIn(BiomeTags.IS_FOREST)) {
            if (this.random.nextBoolean()) {
                return this.random.nextBoolean() ? OwlVariant.WILD : OwlVariant.WILD_GREEN_EYED;
            } else {
                return this.random.nextBoolean() ? OwlVariant.GRAY_OWL : OwlVariant.GRAY_OWL_GREEN_EYED;
            }
        }

        return OwlVariant.WILD;
    }

    @Override public int getAngerTime() { return 0; }
    @Override public void setAngerTime(int angerTime) {}
    @Override public @Nullable UUID getAngryAt() { return null; }
    @Override public void setAngryAt(@Nullable UUID angryAt) {}
    @Override public void chooseRandomAngerTime() {}
    @Override public EntityView method_48926() {return this.getWorld();}

    private void setupAnimationStates() {
        if (this.isSitting()) {
            this.sittingAnimationState.startIfNotRunning(this.age);
            this.idleAnimationState.stop();
        } else {
            this.sittingAnimationState.stop();
            if (this.idleAnimationTimeout <= 0) {
                this.idleAnimationTimeout = this.random.nextInt(40) + 80;
                this.idleAnimationState.start(this.age);
            } else {
                --this.idleAnimationTimeout;
            }
        }
    }

    @Override protected @Nullable SoundEvent getAmbientSound() {
        return ModSounds.OWL_SOUNDS;
    }
    @Override protected float getSoundVolume() {return this.getWorld().isDay() ? 0.25F : 1.0F;}
}