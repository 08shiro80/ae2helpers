package rearth.ae2helpers.mixin.importcard;

import appeng.api.behaviors.StackImportStrategy;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.UpgradeInventories;
import appeng.helpers.patternprovider.PatternProviderLogic;
import appeng.helpers.patternprovider.PatternProviderLogicHost;
import appeng.parts.automation.StackWorldBehaviors;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import rearth.ae2helpers.ae2helpers;
import rearth.ae2helpers.util.IPatternProviderUpgradeHost;
import rearth.ae2helpers.util.IProviderRedstoneHost;
import rearth.ae2helpers.util.ImportCardConfig;
import rearth.ae2helpers.util.PatternProviderImportContext;
import rearth.ae2helpers.util.RedstoneCardConfig;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Mixin(PatternProviderLogic.class)
public abstract class PatternProviderImportMixin implements IPatternProviderUpgradeHost, IProviderRedstoneHost {

    @Shadow @Final private IManagedGridNode mainNode;
    @Shadow @Final private IActionSource actionSource;
    @Shadow @Final private PatternProviderLogicHost host;
    @Shadow public abstract void saveChanges();
    @Shadow public abstract List<IPatternDetails> getAvailablePatterns();
    
    // cached import strategy per provider target side
    @Unique private final Map<Direction, StackImportStrategy> ae2helpers$importStrategies = new EnumMap<>(Direction.class);

    // used to detect config changes (invalidates all cached strategies)
    @Unique private Direction ae2helpers$lastUsedConfigDirection;
    
    @Unique private IUpgradeInventory ae2helpers$upgradeSlots = UpgradeInventories.empty();
    @Unique private final Map<AEKey, Long> ae2helpers$expectedResults = new HashMap<>();

    // Keys the crafting service has actually tracked (getRequestedAmount > 0) at least once.
    // Only then is a later "0" a trustworthy "craft finished" signal.
    @Unique private final java.util.Set<AEKey> ae2helpers$confirmedCrafts = new java.util.HashSet<>();

    // redstone emission state, recomputed each tick; neighbor updates fire only on change
    @Unique private boolean ae2helpers$emitting = false;
    @Unique private boolean ae2helpers$emittingStrong = false;
    @Unique private boolean ae2helpers$wasCraftingActive = false;
    @Unique private long ae2helpers$pulseEndTick = 0;
    
    @Unique private int ae2helpers$cyclesSinceLastCheck = 0;
    @Unique private float ae2helpers$currentCycleDelay = 1f;
    @Unique private static final int AEHELPERS$MAX_CYCLE_DELAY = 10;
    
    @Inject(method = "<init>(Lappeng/api/networking/IManagedGridNode;Lappeng/helpers/patternprovider/PatternProviderLogicHost;I)V",at = @At("TAIL"))
    private void ae2extras$initUpgrade(IManagedGridNode mainNode, PatternProviderLogicHost host, int patternInventorySize, CallbackInfo ci) {
        this.ae2helpers$upgradeSlots = UpgradeInventories.forMachine(ae2helpers.RESULT_IMPORT_CARD, 2, this::ae2helpers$onUpgradesChanged);
    }

    @Unique
    private void ae2helpers$onUpgradesChanged() {
        this.saveChanges();
        // wake the device so the redstone state gets re-checked after a card change
        this.mainNode.ifPresent((grid, node) -> grid.getTickManager().alertDevice(node));
    }
    
    @Inject(method = "pushPattern", at = @At("RETURN"))
    private void ae2helpers$onPushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) {
            if (!ae2helpers$hasImportCard()) return;
            
            for (var output : patternDetails.getOutputs()) {
                if (output != null) {
                    ae2helpers$expectedResults.merge(output.what(), output.amount(), Long::sum);
                }
            }
            
            ae2helpers$currentCycleDelay = 1;
            ae2helpers$cyclesSinceLastCheck = 0;
            this.saveChanges();

            this.mainNode.ifPresent((grid, node) -> grid.getTickManager().alertDevice(node));
        }
    }
    
    @Unique
    private void ae2helpers$syncWithCraftingService() {
        var grid = this.mainNode.getGrid();
        if (grid == null) return;
        
        var craftingService = grid.getCraftingService();
        if (craftingService == null) return;
        
        // forget confirmations for keys we no longer expect
        ae2helpers$confirmedCrafts.retainAll(ae2helpers$expectedResults.keySet());

        var it = ae2helpers$expectedResults.entrySet().iterator();
        var changed = false;

        while (it.hasNext()) {
            var entry = it.next();
            var key = entry.getKey();
            var totalRequested = craftingService.getRequestedAmount(key);

            if (totalRequested > 0) {
                ae2helpers$confirmedCrafts.add(key);
                if (entry.getValue() > totalRequested) {
                    entry.setValue(totalRequested);
                    changed = true;
                }
            } else if (ae2helpers$confirmedCrafts.contains(key)) {
                // getRequestedAmount only becomes a reliable "craft finished" signal after the crafting
                // service has actually tracked this key. A transient 0 (right after pushing, or on world
                // load before the job re-registers) must not drop the expectation and strand results.
                it.remove();
                ae2helpers$confirmedCrafts.remove(key);
                changed = true;
            }
        }

        if (changed) this.saveChanges();
    }
    
    @Inject(method = "doWork", at = @At("RETURN"), cancellable = true)
    private void ae2helpers$onDoWork(CallbackInfoReturnable<Boolean> cir) {
        if (!this.mainNode.isActive()) return;
        
        if (!ae2helpers$hasImportCard()) {
            if (!ae2helpers$expectedResults.isEmpty()) {
                ae2helpers$expectedResults.clear();
                this.saveChanges();
            }
            return;
        }
        
        var config = ae2helpers$getConfig();
        
        // If we are in "Result Only" mode AND have no expectations, we sleep.
        // If "Result Only" is FALSE (Import Everything), we must run even if map is empty.
        if (config.resultsOnly() && ae2helpers$expectedResults.isEmpty()) return;
        
        ae2helpers$cyclesSinceLastCheck++;
        
        if (ae2helpers$cyclesSinceLastCheck >= (int) ae2helpers$currentCycleDelay) {
            ae2helpers$cyclesSinceLastCheck = 0;
            
            var didWork = ae2helpers$doImportWork(config);
            
            if (didWork) {
                ae2helpers$currentCycleDelay = 1;
                cir.setReturnValue(true);
            } else {
                ae2helpers$currentCycleDelay = Math.min(AEHELPERS$MAX_CYCLE_DELAY, ae2helpers$currentCycleDelay * 1.15f);
            }
            
            // Sync only if there is data AND config allows it
            if (!ae2helpers$expectedResults.isEmpty() && config.syncToGrid()) {
                ae2helpers$syncWithCraftingService();
            }
        }
    }

    @Inject(method = "doWork", at = @At("RETURN"))
    private void ae2helpers$redstoneDoWork(CallbackInfoReturnable<Boolean> cir) {
        ae2helpers$updateRedstone();
    }

    @Inject(method = "hasWorkToDo", at = @At("RETURN"), cancellable = true)
    private void ae2helpers$hasWorkToDo(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) return;

        // stay awake to poll the redstone-emission state each tick
        if (ae2helpers$hasRedstoneCard()) {
            cir.setReturnValue(true);
            return;
        }

        // If AE2 thinks it's asleep, check if we need to wake up
        if (ae2helpers$hasImportCard()) {
            var config = ae2helpers$getConfig();
            // Wake up if: "Import All" mode is ON, OR we have specific results waiting
            if (!config.resultsOnly() || !ae2helpers$expectedResults.isEmpty()) {
                cir.setReturnValue(true);
            }
        }
    }
    
    @Unique
    private boolean ae2helpers$doImportWork(ImportCardConfig config) {
        var targets = this.host.getTargets();
        if (targets.isEmpty()) return false;

        var be = this.host.getBlockEntity();
        if (be == null || be.getLevel() == null) return false;

        var level = (ServerLevel) be.getLevel();
        var pos = be.getBlockPos();

        var overriddenSide = config.overriddenDirection();

        // Config direction changed -> drop all cached strategies so they get rebuilt with the new face.
        if (this.ae2helpers$lastUsedConfigDirection != overriddenSide) {
            this.ae2helpers$importStrategies.clear();
            this.ae2helpers$lastUsedConfigDirection = overriddenSide;
        }

        // Drop cached strategies for sides that are no longer valid targets (e.g. push direction changed).
        this.ae2helpers$importStrategies.keySet().removeIf(side -> !targets.contains(side));

        var context = new PatternProviderImportContext(
          this.mainNode.getGrid().getStorageService(),
          this.mainNode.getGrid().getEnergyService(),
          this.actionSource,
          this.ae2helpers$expectedResults,
          config.resultsOnly() // Pass the mode
        );

        // Import from every side the provider pushes to. Only the side(s) with a real machine will
        // yield anything; in "results only" mode the context filter additionally restricts to expected results.
        for (var side : targets) {
            var strategy = this.ae2helpers$importStrategies.computeIfAbsent(side, s -> {
                var targetFace = overriddenSide != null ? overriddenSide : s.getOpposite();
                return StackWorldBehaviors.createImportFacade(level, pos.relative(s), targetFace, (type) -> true);
            });

            strategy.transfer(context);

            if (!context.hasOperationsLeft()) break;
        }

        var importedMap = context.getImportedItems();
        if (!importedMap.isEmpty()) {
            // always track results/expectations in case config is changed
            if (!ae2helpers$expectedResults.isEmpty()) {
                var changed = false;
                var it = ae2helpers$expectedResults.entrySet().iterator();
                
                while (it.hasNext()) {
                    var entry = it.next();
                    var key = entry.getKey();
                    var expected = entry.getValue();
                    
                    var actuallyImported = importedMap.getOrDefault(key, 0L);
                    
                    if (actuallyImported > 0) {
                        var remaining = expected - actuallyImported;
                        if (remaining <= 0) {
                            it.remove();
                        } else {
                            entry.setValue(remaining);
                        }
                        changed = true;
                    }
                }
                
                if (changed) {
                    this.saveChanges();
                }
            }
            return true;
        }
        
        return false;
    }
    
    @Unique
    private ImportCardConfig ae2helpers$getConfig() {
        var stack = ae2helpers$findCard(ae2helpers.RESULT_IMPORT_CARD.get());
        if (stack == null) return ImportCardConfig.DEFAULT;
        return stack.getOrDefault(ae2helpers.IMPORT_CARD_CONFIG.get(), ImportCardConfig.DEFAULT);
    }

    @Unique
    private net.minecraft.world.item.ItemStack ae2helpers$findCard(net.minecraft.world.item.Item card) {
        for (int i = 0; i < ae2helpers$upgradeSlots.size(); i++) {
            var stack = ae2helpers$upgradeSlots.getStackInSlot(i);
            if (!stack.isEmpty() && stack.is(card)) return stack;
        }
        return null;
    }

    @Unique
    private boolean ae2helpers$hasImportCard() {
        return ae2helpers$findCard(ae2helpers.RESULT_IMPORT_CARD.get()) != null;
    }

    @Unique
    private boolean ae2helpers$hasRedstoneCard() {
        return ae2helpers$findCard(ae2helpers.REDSTONE_CARD.get()) != null;
    }

    @Override
    public boolean ae2helpers$isEmittingRedstone() {
        return ae2helpers$emitting;
    }

    @Override
    public boolean ae2helpers$isEmittingStrongRedstone() {
        return ae2helpers$emittingStrong;
    }

    @Override
    public Direction ae2helpers$getRedstoneSide() {
        var config = ae2helpers$getRedstoneConfig();
        return config == null ? null : config.side();
    }

    @Unique
    private RedstoneCardConfig ae2helpers$getRedstoneConfig() {
        var stack = ae2helpers$findCard(ae2helpers.REDSTONE_CARD.get());
        if (stack == null) return null;
        return stack.getOrDefault(ae2helpers.REDSTONE_CARD_CONFIG.get(), RedstoneCardConfig.DEFAULT);
    }

    @Unique
    private boolean ae2helpers$isCraftingActive() {
        var grid = this.mainNode.getGrid();
        if (grid == null) return false;
        var craftingService = grid.getCraftingService();
        if (craftingService == null) return false;

        for (var pattern : this.getAvailablePatterns()) {
            for (var output : pattern.getOutputs()) {
                if (output != null && craftingService.isRequesting(output.what())) {
                    return true;
                }
            }
        }
        return false;
    }

    @Unique
    private void ae2helpers$updateRedstone() {
        var be = this.host.getBlockEntity();
        var level = be != null ? be.getLevel() : null;
        var config = ae2helpers$getRedstoneConfig();

        boolean newEmitting;
        if (config == null || level == null) {
            newEmitting = false;
            ae2helpers$wasCraftingActive = false;
        } else {
            var active = ae2helpers$isCraftingActive();
            switch (config.mode()) {
                case INVERTED -> newEmitting = !active;
                case PULSE -> {
                    if (active != ae2helpers$wasCraftingActive) {
                        ae2helpers$pulseEndTick = level.getGameTime() + Math.max(1, config.pulseLength());
                    }
                    newEmitting = level.getGameTime() < ae2helpers$pulseEndTick;
                }
                default -> newEmitting = active;
            }
            ae2helpers$wasCraftingActive = active;
        }
        var newStrong = newEmitting && config != null && config.strongSignal();

        if (newEmitting == ae2helpers$emitting && newStrong == ae2helpers$emittingStrong) return;
        ae2helpers$emitting = newEmitting;
        ae2helpers$emittingStrong = newStrong;

        if (level == null || level.isClientSide) return;
        level.updateNeighborsAt(be.getBlockPos(), be.getBlockState().getBlock());
    }
    
    @Inject(method = "clearContent", at = @At("HEAD"))
    private void ae2helpers$onClearContent(CallbackInfo ci) {
        this.ae2helpers$importStrategies.clear();
        this.ae2helpers$lastUsedConfigDirection = null;
        this.ae2helpers$expectedResults.clear();
        this.ae2helpers$confirmedCrafts.clear();
        this.ae2helpers$upgradeSlots.clear();
    }
    
    @Inject(method = "addDrops", at = @At("TAIL"))
    private void ae2extras$dropUpgrade(List<ItemStack> drops, CallbackInfo ci) {
        for (var slot : this.ae2helpers$upgradeSlots) {
            if (!slot.isEmpty()) {
                drops.add(slot);
            }
        }
    }
    
    @Inject(method = "writeToNBT", at = @At("TAIL"))
    private void ae2helpers$writeToNBT(CompoundTag tag, HolderLookup.Provider registries, CallbackInfo ci) {
        if (!ae2helpers$expectedResults.isEmpty()) {
            var list = new ListTag();
            ae2helpers$expectedResults.forEach((key, amount) -> {
                list.add(GenericStack.writeTag(registries, new GenericStack(key, amount)));
            });
            tag.put("ae2helpers_expected_results", list);
        }
        ae2helpers$upgradeSlots.writeToNBT(tag, "ae2helperupgrades", registries);
    }
    
    @Inject(method = "readFromNBT", at = @At("TAIL"))
    private void ae2helpers$readFromNBT(CompoundTag tag, HolderLookup.Provider registries, CallbackInfo ci) {
        ae2helpers$expectedResults.clear();
        if (tag.contains("ae2helpers_expected_results")) {
            var list = tag.getList("ae2helpers_expected_results", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                var stack = GenericStack.readTag(registries, list.getCompound(i));
                if (stack != null) {
                    ae2helpers$expectedResults.put(stack.what(), stack.amount());
                }
            }
        }
        ae2helpers$upgradeSlots.readFromNBT(tag, "ae2helperupgrades", registries);
    }
    
    @Override
    public IUpgradeInventory ae2helpers$getUpgradeInventory() {
        return ae2helpers$upgradeSlots;
    }
}