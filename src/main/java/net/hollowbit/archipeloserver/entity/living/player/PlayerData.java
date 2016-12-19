package net.hollowbit.archipeloserver.entity.living.player;

import java.sql.Date;

import net.hollowbit.archipeloserver.items.Item;

public class PlayerData {
	
	public String uuid;
	public String name;
	public String bhUuid;
	public float x, y;
	public String island, map;
	public Item[] uneditableEquippedInventory;
	public Item[] equippedInventory;
	public Item[] cosmeticInventory;
	public Item[] bankInventory;
	public Item[] inventory;
	public Date lastPlayed, creationDate;
	public String flags;
	
}
