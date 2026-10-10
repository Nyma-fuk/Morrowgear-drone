package jp.morrowgear.drone.carrier;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

/** Eight physical hardpoints. Modules never stack and retain their item identity while attached. */
public final class CarrierExpansionBay {
    public record Attached(java.util.UUID id, CarrierExpansionPolicy.Type type, int hardpoint) {}
    public record MissileCell(int hardpoint, int cell) {
        public MissileCell {
            CarrierExpansionPolicy.checkedHardpoint(hardpoint);
            if (cell < 0 || cell >= CarrierMissileMagazinePolicy.CAPACITY)
                throw new IllegalArgumentException("Invalid missile cell " + cell);
        }
    }
    private final ItemStack[] modules = new ItemStack[CarrierExpansionPolicy.HARDPOINTS];
    private final Runnable dirty;

    public CarrierExpansionBay(List<ItemStack> saved, Runnable dirty) {
        this.dirty = dirty;
        java.util.Arrays.fill(modules, ItemStack.EMPTY);
        for (int i=0;i<Math.min(saved.size(),modules.length);i++) modules[i]=saved.get(i).copyWithCount(1);
        migrateLegacyMissileStations();
    }

    private void migrateLegacyMissileStations(){
        for(int slot=0;slot<modules.length;slot++){
            var identity=CarrierExpansionItemData.read(modules[slot]);
            if(identity==null||identity.type()!=CarrierExpansionPolicy.Type.MISSILE_BATTERY
                ||CarrierCargoVisualPolicy.ordnanceCapable(slot))continue;
            int replacement=-1;
            for(int candidate=0;candidate<modules.length;candidate++)
                if(modules[candidate].isEmpty()&&CarrierCargoVisualPolicy.ordnanceCapable(candidate)){
                    replacement=candidate;break;
                }
            if(replacement>=0){modules[replacement]=modules[slot];modules[slot]=ItemStack.EMPTY;}
        }
    }
    public int firstFree() { return firstFree(CarrierExpansionPolicy.Type.CARGO); }
    public int firstFree(CarrierExpansionPolicy.Type type) {
        for(int i=0;i<modules.length;i++)
            if(modules[i].isEmpty() && compatible(i,type)) return i;
        return -1;
    }
    public static boolean compatible(int slot, CarrierExpansionPolicy.Type type) {
        CarrierExpansionPolicy.checkedHardpoint(slot);
        return type == CarrierExpansionPolicy.Type.CARGO || CarrierCargoVisualPolicy.ordnanceCapable(slot);
    }
    public boolean occupied(int slot) { return slot>=0&&slot<modules.length&&!modules[slot].isEmpty(); }
    public ItemStack get(int slot) { CarrierExpansionPolicy.checkedHardpoint(slot); return modules[slot]; }
    public boolean install(int slot, ItemStack module) {
        CarrierExpansionPolicy.checkedHardpoint(slot);
        var identity=CarrierExpansionItemData.read(module);
        if(module.isEmpty()||occupied(slot)||identity==null||!compatible(slot,identity.type())) return false;
        modules[slot]=module.copyWithCount(1); dirty.run(); return true;
    }
    public ItemStack remove(int slot) {
        CarrierExpansionPolicy.checkedHardpoint(slot);
        ItemStack result=modules[slot]; modules[slot]=ItemStack.EMPTY;
        if(!result.isEmpty()) dirty.run(); return result;
    }
    public int count(CarrierExpansionPolicy.Type type) {
        int count=0;
        for(ItemStack stack:modules){var id=CarrierExpansionItemData.read(stack);if(id!=null&&id.type()==type)count++;}
        return count;
    }
    public int highest(CarrierExpansionPolicy.Type type) {
        for(int i=modules.length-1;i>=0;i--){var id=CarrierExpansionItemData.read(modules[i]);if(id!=null&&id.type()==type)return i;}
        return -1;
    }
    public int slot(java.util.UUID moduleId) {
        if(moduleId==null)return -1;
        for(int i=0;i<modules.length;i++){var id=CarrierExpansionItemData.read(modules[i]);if(id!=null&&id.id().equals(moduleId))return i;}
        return -1;
    }
    public List<ItemStack> snapshot() {
        var result=new ArrayList<ItemStack>(modules.length);
        for(ItemStack stack:modules)result.add(stack.copy());
        return result;
    }
    public List<Attached> attached() {
        var result=new ArrayList<Attached>();
        for(int i=0;i<modules.length;i++){var id=CarrierExpansionItemData.read(modules[i]);if(id!=null)result.add(new Attached(id.id(),id.type(),i));}
        return List.copyOf(result);
    }

    public int missiles() {
        int count=0;
        for(int slot=0;slot<modules.length;slot++){
            if(!CarrierCargoVisualPolicy.ordnanceCapable(slot))continue;
            ItemStack module=modules[slot];
            var id=CarrierExpansionItemData.read(module);
            if(id==null||id.type()!=CarrierExpansionPolicy.Type.MISSILE_BATTERY)continue;
            NonNullList<ItemStack> cells=NonNullList.withSize(CarrierMissileContainerBlockEntity.UI_SIZE,ItemStack.EMPTY);
            module.getOrDefault(DataComponents.CONTAINER,ItemContainerContents.EMPTY).copyInto(cells);
            for(int i=0;i<CarrierMissileMagazinePolicy.CAPACITY;i++)if(!cells.get(i).isEmpty())count++;
        }
        return count;
    }

    public int missiles(int slot){
        CarrierExpansionPolicy.checkedHardpoint(slot); ItemStack module=modules[slot];
        var id=CarrierExpansionItemData.read(module);
        if(id==null||id.type()!=CarrierExpansionPolicy.Type.MISSILE_BATTERY)return 0;
        NonNullList<ItemStack> cells=NonNullList.withSize(CarrierMissileContainerBlockEntity.UI_SIZE,ItemStack.EMPTY);
        module.getOrDefault(DataComponents.CONTAINER,ItemContainerContents.EMPTY).copyInto(cells);
        int count=0;for(int i=0;i<CarrierMissileMagazinePolicy.CAPACITY;i++)if(!cells.get(i).isEmpty())count++;
        return count;
    }

    /** Stable source ordering for a launch transaction. Legacy outer magazines remain unloadable but cannot fire. */
    public List<MissileCell> availableMissiles() {
        var result=new ArrayList<MissileCell>();
        for(int slot=0;slot<modules.length;slot++){
            if(!CarrierCargoVisualPolicy.ordnanceCapable(slot))continue;
            ItemStack module=modules[slot];
            var id=CarrierExpansionItemData.read(module);
            if(id==null||id.type()!=CarrierExpansionPolicy.Type.MISSILE_BATTERY)continue;
            NonNullList<ItemStack> cells=NonNullList.withSize(CarrierMissileContainerBlockEntity.UI_SIZE,ItemStack.EMPTY);
            module.getOrDefault(DataComponents.CONTAINER,ItemContainerContents.EMPTY).copyInto(cells);
            for(int cell=0;cell<CarrierMissileMagazinePolicy.CAPACITY;cell++)
                if(!cells.get(cell).isEmpty())result.add(new MissileCell(slot,cell));
        }
        return List.copyOf(result);
    }

    /** Removes the exact physical round reserved by the launch transaction. */
    public boolean consumeMissile(MissileCell source) {
        ItemStack module=modules[source.hardpoint()];
        var id=CarrierExpansionItemData.read(module);
        if(id==null||id.type()!=CarrierExpansionPolicy.Type.MISSILE_BATTERY
            ||!CarrierCargoVisualPolicy.ordnanceCapable(source.hardpoint()))return false;
        NonNullList<ItemStack> cells=NonNullList.withSize(CarrierMissileContainerBlockEntity.UI_SIZE,ItemStack.EMPTY);
        module.getOrDefault(DataComponents.CONTAINER,ItemContainerContents.EMPTY).copyInto(cells);
        if(cells.get(source.cell()).isEmpty())return false;
        cells.set(source.cell(),ItemStack.EMPTY);
        module.set(DataComponents.CONTAINER,ItemContainerContents.fromItems(cells));
        dirty.run();
        return true;
    }

    /** Compatibility helper for callers that do not need source telemetry. */
    public boolean consumeMissile() {
        var available=availableMissiles();
        return !available.isEmpty()&&consumeMissile(available.getFirst());
    }
}
