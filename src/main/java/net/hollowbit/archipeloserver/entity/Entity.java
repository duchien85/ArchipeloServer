package net.hollowbit.archipeloserver.entity;

import java.util.ArrayList;

import com.badlogic.gdx.math.Vector2;

import net.hollowbit.archipeloserver.ArchipeloServer;
import net.hollowbit.archipeloserver.entity.EntityAnimationManager.EntityAnimationObject;
import net.hollowbit.archipeloserver.entity.living.Player;
import net.hollowbit.archipeloserver.network.packets.PopupTextPacket;
import net.hollowbit.archipeloserver.network.packets.TeleportPacket;
import net.hollowbit.archipeloserver.particles.types.HealthParticles;
import net.hollowbit.archipeloserver.tools.entity.Location;
import net.hollowbit.archipeloserver.tools.event.events.editable.EntityDeathEvent;
import net.hollowbit.archipeloserver.tools.event.events.editable.EntityHealEvent;
import net.hollowbit.archipeloserver.tools.event.events.editable.EntityInteractionEvent;
import net.hollowbit.archipeloserver.tools.event.events.editable.EntityTeleportEvent;
import net.hollowbit.archipeloserver.world.Map;
import net.hollowbit.archipeloserver.world.World;
import net.hollowbit.archipeloshared.CollisionRect;
import net.hollowbit.archipeloshared.Direction;
import net.hollowbit.archipeloshared.EntitySnapshot;
import net.hollowbit.archipeloshared.Point;

public abstract class Entity {

	public static final float DAMAGE_FLASH_DURATION = 0.2f;
	
	protected String name;
	protected EntityType entityType;
	protected int style;
	protected Location location;
	protected EntitySnapshot changes;
	protected EntityLog log;
	protected EntityAnimationManager animationManager;
	protected ArrayList<EntityComponent> components;
	protected EntityAudioManager audioManager;
	protected float health;
	
	public Entity () {
		components = new ArrayList<EntityComponent>();
	}
	
	public void create (String name, int style, Location location, EntityType entityType) {
		this.name = name;
		this.style = style;
		this.entityType = entityType;
		this.location = location;
		
		this.health = entityType.getMaxHealth();
		
		changes = new EntitySnapshot(this.name, this.entityType.getId(), true);
		log = new EntityLog();
		animationManager = new EntityAnimationManager(this, entityType.getDefaultAnimationId(), "", 0);
		audioManager = new EntityAudioManager(this);
	}
	
	public void create (EntitySnapshot fullSnapshot, Map map, EntityType entityType) {
		this.name = fullSnapshot.name;
		this.entityType = entityType;
		Point pos = fullSnapshot.getObject("pos", new Point(), Point.class);
		this.location =  new Location(map, new Vector2(pos.x, pos.y), fullSnapshot.getInt("direction", 0));
		
		//Make sure style is valid
		this.style = fullSnapshot.getInt("style", 0);
		if (this.style >= entityType.getNumberOfStyles()) {
			this.style = 0;
			ArchipeloServer.getServer().getLogger().caution("Entity " + this.name + " on map " + this.getMap().getName() + " has a bad style attribute.");
		}
		
		this.health = fullSnapshot.getFloat("health", entityType.getMaxHealth());
		if (health > this.getMaxHealth())
			health = this.getMaxHealth();
		
		changes = new EntitySnapshot(this.name, this.entityType.getId(), true);
		log = new EntityLog();
		animationManager = new EntityAnimationManager(this, fullSnapshot);
		audioManager = new EntityAudioManager(this);
	}
	
	/**
	 * Should only be called in the EntityAnimationManager.
	 * This is a listener to handle when the current non-looping animation has finished.
	 * Usually, this should return a looping animation just to be safe.
	 * Safe to return null in many cases.
	 */
	public abstract EntityAnimationObject animationCompleted (String animationId);
	
	public void tick20 (float deltaTime) {
		animationManager.update(deltaTime);
		log.removeOldEntitySnapshotsFromLog();
		
		for (EntityComponent component : components)
			component.tick20(deltaTime);
	}
	
	public void tick60 (float deltaTime) {
		log.addEntry(new EntityLogEntry(location.getX(), location.getY(), getSpeed()));
		
		for (EntityComponent component : components)
			component.tick60(deltaTime);
	}
	
	protected void interactWith (Entity target, String theirCollisionRectName, String yourCollisionRectName, EntityInteractionType interactionType) {
		EntityInteractionEvent event = new EntityInteractionEvent(this, target, theirCollisionRectName, yourCollisionRectName, interactionType);
		event.trigger();
		
		if (!event.wasCancelled())
			target.interactFrom(this, theirCollisionRectName, yourCollisionRectName, interactionType);
		event.close();
	}
	
	protected void interactFrom (Entity entity, String yourCollisionRectName, String theirCollisionRectName, EntityInteractionType interactionType) {}
	
	public String getName () {
		return name;
	}
	
	/**
	 * Change the entities style on the fly. It also makes sure the style is available first.
	 * @param style
	 */
	public void setStyle (int style) {
		if (style < entityType.getNumberOfStyles()) {
			this.style = style;
			this.changes.putInt("style", style);
		}
	}
	
	public int getStyle () {
		return style;
	}
	
	/**
	 * Proper way to remove an entity.
	 */
	public void remove () {
		location.getMap().removeEntityUnsafe(this);
		
		for (EntityComponent component : components)
			component.remove();
	}
	
	/**
	 * This is used by certain entities which don't always want a collision rect to be hard.
	 * Ex: Like a locked door that becomes unlocked for some players.
	 * @param player
	 * @param rectName
	 * @return
	 */
	public boolean ignoreHardnessOfCollisionRects (Player player, String rectName) {
		boolean ignore = false;
		for (EntityComponent component : components) {
			if (component.ignoreHardnessOfCollisionRects(player, rectName))
				ignore = true;
		}
		return ignore;
	}
	
	/**
	 * InterpSnapshots are for things like position that can be interpolated between. Packet dropping should not be an issue for these data values.
	 * @return
	 */
	public EntitySnapshot getInterpSnapshot () {
		EntitySnapshot snapshot = new EntitySnapshot(this.name, this.entityType.getId(), true);
		audioManager.applyToInterpSnapshot(snapshot);
		
		for (EntityComponent component : components)
			component.editInterpSnapshot(snapshot);
		return snapshot;
	}
	
	/**
	 * Changes since last tick. Unlike InterpSnapshots, these are changes that MUST be applied.
	 * @return
	 */
	public EntitySnapshot getChangesSnapshot () {
		return changes;
	}
	
	/**
	 * Full data of an entity. This is used for EntityAddPackets.
	 * @return
	 */
	public EntitySnapshot getFullSnapshot () {
		EntitySnapshot snapshot = new EntitySnapshot(this.name, this.entityType.getId(), false);
		snapshot.putObject("pos", new Point(this.getX(), this.getY()));
		snapshot.putInt("direction", this.getLocation().getDirectionInt());
		snapshot.putInt("style", style);
		if (this.getEntityType().showHealthBar())
			snapshot.putFloat("health", health);
		animationManager.applyToEntityFullSnapshot(snapshot);
		
		for (EntityComponent component : components)
			component.editFullSnapshot(snapshot);
		return snapshot;
	}
	
	/**
	 * Get a snapshot of an entity to save them when map unloads
	 * @return
	 */
	public EntitySnapshot getSaveSnapshot () {
		EntitySnapshot snapshot = new EntitySnapshot(this.name, this.entityType.getId(), false);
		snapshot.putObject("pos", new Point(this.getX(), this.getY()));
		snapshot.putInt("direction", this.getLocation().getDirectionInt());
		snapshot.putInt("style", style);
		snapshot.putFloat("health", health);
		
		for (EntityComponent component : components)
			component.editSaveSnapshot(snapshot);
		return snapshot;
	}
	
	public Location getLocation () {
		return location;
	}
	
	public void teleport (float x, float y) {
		teleport(x, y, location.getDirection());
	}
	
	public void teleport (float x, float y, Direction direction) {
		teleport(x, y, direction, location.getMap().getName());
	}
	
	public void teleport (Location location) {
		teleport(location.getX(), location.getY(), location.getDirection(), location.getMap().getName());
	}
	
	/**
	 * Teleport this entity to another one
	 * @param entity
	 */
	public void teleportTo (Entity entity) {
		teleport(entity.getLocation());
	}
	
	/**
	 * Full teleport. Loads new islands and maps if necessary.
	 * @param x
	 * @param y
	 * @param direction
	 * @param mapName
	 */
	public void teleport (float x, float y, Direction direction, String mapName) {
		Vector2 newPos = new Vector2(x, y);
		
		EntityTeleportEvent event = new EntityTeleportEvent(this, newPos, location.pos, location.map, mapName, location.getDirection(), direction);
		event.trigger();
		if (event.wasCancelled()) {
			event.close();
			return;
		}
		
		newPos.x = event.getNewPos().x;
		newPos.y = event.getNewPos().y;
		direction = event.getNewDirection();
		mapName = event.getNewMap();
		
		boolean mapChanged = !location.getMap().getName().equals(mapName);
		
		World world = ArchipeloServer.getServer().getWorld();
		Map map = null;
		
		if (mapChanged) {
			if (!world.isMapLoaded(mapName)) {
				if (!world.loadMap(mapName)) {
					if (isPlayer()) {
						Player p = (Player) this;
						p.sendPacket(new PopupTextPacket("Unable to teleport.", PopupTextPacket.Type.NORMAL));
						event.cancel();
						event.close();
						return;
					}
				}
			}
			map = world.getMap(mapName);
			
			//Add player to other map then remove it from current
			map.addEntity(this);
			location.getMap().removeEntityUnsafe(this);
			location.setMap(map);
		} else
			map = location.getMap();
		
		newPos.add(-this.entityType.getFootstepOffsetX(), -this.entityType.getFootstepOffsetY());
		location.set(newPos);
		location.setDirection(direction);
		
		for (Player player : location.getMap().duplicatePlayerList()) {
			player.sendPacket(new TeleportPacket(this.name, newPos.x, newPos.y, this.location.getDirectionInt(), mapChanged));
		}
		
		log.clearAll();
		event.close();
	}
	
	/**
	 * Plays the specified sound at this entity's location.
	 * @param soundId
	 */
	public void playSoundAtLocation(String soundId) {
		location.getMap().playSound(soundId, location.getX(), location.getY());
	}
	
	public boolean isAlive () {
		return false;
	}
	
	/**
	 * Returns whether this entity is a player type.
	 * @return
	 */
	public boolean isPlayer () {
		return false;
	}
	
	public EntityType getEntityType () {
		return entityType;
	}
	
	public float getX () {
		return location.getX();
	}
	
	public float getY () {
		return location.getY();
	}
	
	public Map getMap () {
		return location.getMap();
	}
	
	public float getHealth () {
		return health;
	}
	
	public int getMaxHealth () {
		return entityType.getMaxHealth();
	}
	
	/**
	 * Can be negative to damage this entity.
	 * May trigger an entity death event.
	 * @param amount
	 * @return Whether entity died
	 */
	public boolean heal(float amount) {
		return this.heal(amount, null);
	}
	
	/**
	 * Can be negative to damage this entity.
	 * May trigger an entity death event.
	 * @param amount
	 * @param healer
	 * @return Whether entity died
	 */
	public boolean heal(float amount, Entity healer) {
		EntityHealEvent eventHeal = new EntityHealEvent(amount, this, healer);
		eventHeal.trigger();
		
		if (eventHeal.wasCancelled()) {
			eventHeal.close();
			return false;
		}
		
		float oldHealth = this.health;
		this.health += eventHeal.getAmount();
		healer = eventHeal.getHealer();
		eventHeal.close();
		
		location.map.spawnParticles(new HealthParticles(this, (int) amount));
		
		if (health <= 0) {
			//Trigger death event
			EntityDeathEvent event = new EntityDeathEvent(this, healer, oldHealth, this.health);
			event.trigger();
			
			if (event.wasCancelled()) {//Canceled, reset health or set it to new value
				if (event.isNewHealthSet())
					this.health = event.getNewHealth();
				else
					this.health = oldHealth;
			} else {//Event not canceled, remove entity
				this.remove();
				event.close();
				return true;
			}
			event.close();
		} else {
			if (amount < 0)//Play flash animation depending if this was a heal or damage
				this.changes.putBoolean("flash", true);
			else
				this.changes.putBoolean("flash", false);
			
			//Entity not dead. Just clamp health and update health bars
			if (health > this.getMaxHealth())
				health = this.getMaxHealth();
			
			if (this.getEntityType().showHealthBar())
				this.changes.putFloat("health", health);
		}
		return false;
	}
	
	/**
	 * Returns array of all collisions rects for this entity
	 * @return
	 */
	public CollisionRect[] getCollisionRects () {
		return entityType.getCollisionRects(location.getX(), location.getY());
	}
	
	/**
	 * Get collision rects of this entity considering it were at a specified position.
	 * @param potentialPosition
	 * @return
	 */
	public CollisionRect[] getCollisionRects (Vector2 potentialPosition) {
		return entityType.getCollisionRects(potentialPosition.x, potentialPosition.y);
	}
	
	/**
	 * Get entity collision rects at a certain point in time. Maximum 2 seconds ago.
	 * @param time
	 * @return
	 */
	public CollisionRect[] getCollisionRects (long time) {
		Vector2 pos = log.getPositionAtTimestamp(time);
		if (pos == null)
			return getCollisionRects();
		else
			return getCollisionRects(pos);
	}
	
	/**
	 * Exact center point of the entities view rect.
	 * @return
	 */
	public Vector2 getCenterPoint () {
		CollisionRect viewRect = entityType.getViewRect(location.getX(), location.getY());
		return new Vector2(location.getX() + viewRect.width / 2, location.getY() + viewRect.height / 2);
	}
	
	public CollisionRect getViewRect() {
		return entityType.getViewRect(location.getX(), location.getY());
	}
	
	public float getSpeed () {
		return entityType.getSpeed();
	}
	
	public EntityAudioManager getAudioManager() {
		return audioManager;
	}
	
	public EntityAnimationManager getAnimationManager() {
		return animationManager;
	}
	
	public float getFootX () {
		return location.getX() + entityType.getFootstepOffsetX();
	}
	
	public float getFootY () {
		return location.getY() + entityType.getFootstepOffsetY();
	}
	
	/**
	 * Proper way to change an entity's direction
	 * @param newDirection
	 */
	public void setDirection(Direction newDirection) {
		Direction oldDirection = location.direction;
		if (newDirection != oldDirection) {
			location.direction = newDirection;
			changes.putInt("direction", location.getDirectionInt());
		}
	}
	
	/**
	 * Returns the tile which this entities feet is stepping on
	 * @return
	 */
	public Vector2 getFeetTile () {
		return new Vector2(getFootX() / ArchipeloServer.TILE_SIZE, getFootY() / ArchipeloServer.TILE_SIZE);
	}
	
	public float getTopOfHead() {
		return location.getY() + entityType.getViewHeight() - entityType.getHeadOffsetFromTop();
	}
	
	@Override
	public boolean equals(Object obj) {
		if (!(obj instanceof Entity))
			return false;
		
		Entity entity = (Entity) obj;
		return entity.name.equals(this.name);
	}
	
}
