package com.dolzhnik.debtmod.block;

import net.fabricmc.fabric.api.object.builder.v1.block.FabricBlockSettings;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.sound.BlockSoundGroup;

/** Выжженная земля: чёрный пепел с тлеющими прожилками. Остаётся за огненным взыскателем. */
public class ScorchedEarthBlock extends Block {
    public ScorchedEarthBlock() {
        super(FabricBlockSettings.create()
                .mapColor(MapColor.BLACK)
                .strength(0.5f)
                .dropsNothing()
                .sounds(BlockSoundGroup.GRAVEL));
    }
}
