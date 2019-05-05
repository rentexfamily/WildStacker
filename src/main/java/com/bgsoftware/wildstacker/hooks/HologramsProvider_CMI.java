package com.bgsoftware.wildstacker.hooks;

import com.Zrips.CMI.CMI;
import com.Zrips.CMI.Modules.Holograms.CMIHologram;
import com.Zrips.CMI.Modules.Holograms.HologramManager;
import com.bgsoftware.wildstacker.WildStackerPlugin;
import com.bgsoftware.wildstacker.api.objects.StackedBarrel;
import com.bgsoftware.wildstacker.api.objects.StackedObject;
import com.bgsoftware.wildstacker.api.objects.StackedSpawner;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.ArrayList;
import java.util.Collections;

public final class HologramsProvider_CMI implements HologramsProvider {

    private WildStackerPlugin plugin = WildStackerPlugin.getPlugin();
    private HologramManager hologramManager = CMI.getInstance().getHologramManager();

    public HologramsProvider_CMI(){
        WildStackerPlugin.log(" - Using CMI as HologramsProvider.");
    }

    @Override
    public void createHologram(StackedObject stackedObject, String line) {
        Location location = null;

        if(stackedObject instanceof StackedSpawner)
            location = ((StackedSpawner) stackedObject).getLocation();
        else if(stackedObject instanceof StackedBarrel)
            location = ((StackedBarrel) stackedObject).getLocation();

        if(location != null) {
            createHologram(location.add(0.5, 1, 0.5), line);
        }
    }

    @Override
    public void createHologram(Location location, String line) {
        CMIHologram hologram = new CMIHologram("WS-" + location.toString(), location);
        hologramManager.addHologram(hologram);
        hologram.setLines(Collections.singletonList(line));
        hologramManager.resetHoloForAllPlayers(hologram);
        hologram.updatePages();
        hologramManager.save();
    }

    @Override
    public void deleteHologram(StackedObject stackedObject) {
        Location location = null;

        if(stackedObject instanceof StackedSpawner)
            location = ((StackedSpawner) stackedObject).getLocation();
        else if(stackedObject instanceof StackedBarrel)
            location = ((StackedBarrel) stackedObject).getLocation();

        if(location != null) {
            deleteHologram(location.add(0.5, 1, 0.5));
        }
    }

    @Override
    public void deleteHologram(Location location) {
        if(!isHologram(location))
            return;

        CMIHologram hologram = getHologram(location);
        hologram.setLines(new ArrayList<>());
        hologramManager.resetHoloForAllPlayers(hologram);
        hologram.updatePages();
        hologramManager.removeHolo(hologram);
    }

    @Override
    public void changeLine(StackedObject stackedObject, String newLine, boolean createIfNull) {
        Location location = null;

        if(stackedObject instanceof StackedSpawner)
            location = ((StackedSpawner) stackedObject).getLocation();
        else if(stackedObject instanceof StackedBarrel)
            location = ((StackedBarrel) stackedObject).getLocation();

        if(location != null) {
            changeLine(location.add(0.5, 1, 0.5), newLine, createIfNull);
        }
    }

    @Override
    public void changeLine(Location location, String newLine, boolean createIfNull) {
        CMIHologram hologram = getHologram(location);

        if(hologram == null) {
            if(!createIfNull)
                return;
            createHologram(location, newLine);
            return;
        }

        hologram.setLines(Collections.singletonList(newLine));
        hologramManager.resetHoloForAllPlayers(hologram);
        hologram.updatePages();
        hologramManager.save();
    }

    @Override
    public void clearHolograms() {
        for(CMIHologram hologram : hologramManager.getHolograms().values()){
            Block underBlock = hologram.getLoc().getBlock().getRelative(BlockFace.DOWN);
            if(!plugin.getSystemManager().isStackedSpawner(underBlock) && !plugin.getSystemManager().isStackedBarrel(underBlock)) {
                hologramManager.removeHolo(hologram);
                break;
            }
        }
    }

    @Override
    public boolean isHologram(Location location) {
        return getHologram(location) != null;
    }

    private CMIHologram getHologram(Location location){
        return hologramManager.getByName("WS-" + location.toString());
    }

}
