package com.bioxx.tfc2.blocks.terrain;

import net.minecraft.block.material.Material;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.properties.PropertyEnum;
import net.minecraft.block.state.BlockState;
import net.minecraft.block.state.IBlockState;
import net.minecraft.creativetab.CreativeTabs;

import com.bioxx.tfc2.api.types.OreType;
import com.bioxx.tfc2.blocks.BlockTerra;

public class BlockOre extends BlockTerra
{
	public static PropertyEnum META_PROPERTY = PropertyEnum.create("ore", OreType.class);

	public BlockOre()
	{
		super(Material.ground, META_PROPERTY);
		this.setCreativeTab(CreativeTabs.tabBlock);
	}

	@Override
	protected BlockState createBlockState()
	{
		return new BlockState(this, new IProperty[]{META_PROPERTY});
	}

	@Override
	public IBlockState getStateFromMeta(int meta)
	{
		return this.getDefaultState().withProperty(META_PROPERTY, OreType.fromMeta(meta));
	}

	@Override
	public int getMetaFromState(IBlockState state)
	{
		return ((OreType)state.getValue(META_PROPERTY)).getMeta();
	}
}
