package com.hbm.tileentity.turret;

import java.util.ArrayList;
import java.util.List;

import com.hbm.entity.projectile.EntityArtilleryShell;
import com.hbm.handler.BulletConfigSyncingUtil;
import com.hbm.inventory.container.ContainerTurretBase;
import com.hbm.inventory.gui.GUITurretArty;
import com.hbm.items.ModItems;
import com.hbm.lib.Library;
import com.hbm.packet.AuxParticlePacketNT;
import com.hbm.packet.PacketDispatcher;
import com.hbm.tileentity.IGUIProvider;

import cpw.mods.fml.common.network.NetworkRegistry.TargetPoint;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.item.Item;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

public class TileEntityTurretArty extends TileEntityTurretBaseNT implements IGUIProvider {
	
	public short mode = 0;
	public static final short MODE_ARTILLERY = 0;
	public static final short MODE_CANNON = 1;
	public static final short MODE_MANUAL = 2;
	private boolean didJustShoot = false;
	private boolean retracting = false;
	public double barrelPos = 0;
	public double lastBarrelPos = 0;
	
	private List<Vec3> targetQueue = new ArrayList();

	static List<Integer> configs = new ArrayList();
	
	static {
		configs.add(BulletConfigSyncingUtil.SHELL_NORMAL);
		configs.add(BulletConfigSyncingUtil.SHELL_EXPLOSIVE);
		configs.add(BulletConfigSyncingUtil.SHELL_AP);
		configs.add(BulletConfigSyncingUtil.SHELL_DU);
		configs.add(BulletConfigSyncingUtil.SHELL_W9);
	}
	
	public void enqueueTarget(double x, double y, double z) {
		this.targetQueue.add(Vec3.createVectorHelper(x, y, z));
	}
	
	@Override
	protected List<Integer> getAmmoList() {
		return configs;
	}

	@Override
	public String getName() {
		return "container.turretArty";
	}

	@Override
	public long getMaxPower() {
		return 100000;
	}

	@Override
	public double getBarrelLength() {
		return 9D;
	}

	@Override
	public double getAcceptableInaccuracy() {
		return 0;
	}
	
	@Override
	public double getHeightOffset() {
		return 3D;
	}

	@Override
	public double getDecetorRange() {
		return this.mode == this.MODE_CANNON ? 128D : 3000D;
	}
	
	@Override
	public double getDecetorGrace() {
		return this.mode == this.MODE_CANNON ? 32D : 250D;
	}

	@Override
	public double getTurretYawSpeed() {
		return 1D;
	}

	@Override
	public double getTurretPitchSpeed() {
		return 0.5D;
	}
	
	@Override
	public double getTurretDepression() {
		return 30D;
	}

	@Override
	public double getTurretElevation() {
		return 90D;
	}

	@Override
	protected void seekNewTarget() {
		super.seekNewTarget();
	}
	
	@Override
	public boolean entityInLOS(Entity e) {
		
		if(this.mode == this.MODE_CANNON) {
			return super.entityInLOS(e);
		} else {
			
			Vec3 pos = this.getTurretPos();
			Vec3 ent = this.getEntityPos(e);
			Vec3 delta = Vec3.createVectorHelper(ent.xCoord - pos.xCoord, ent.yCoord - pos.yCoord, ent.zCoord - pos.zCoord);
			double length = delta.lengthVector();
			
			if(length < this.getDecetorGrace() || length > this.getDecetorRange() * 1.1) //the latter statement is only relevant for entities that have already been detected
				return false;
			
			int height = worldObj.getHeightValue((int) Math.floor(e.posX), (int) Math.floor(e.posZ));
			return height < (e.posY + e.height);
		}
	}
	
	@Override
	protected void alignTurret() {

		Vec3 pos = this.getTurretPos();
		
		Vec3 barrel = Vec3.createVectorHelper(this.getBarrelLength(), 0, 0);
		barrel.rotateAroundZ((float) -this.rotationPitch);
		barrel.rotateAroundY((float) -(this.rotationYaw + Math.PI * 0.5));
		/*
		 * This is done to compensate for the barrel length, as this small deviation has a huge impact in both modes at longer ranges.
		 * The consequence of this is that using the >before< angle of the barrel as an approximation can lead to problems at closer range,
		 * as the math tries to properly calculate the >after< angle. This should not be a problem due to the etector grace distance being
		 * rather high, but it is still important to note.
		 */
		pos.xCoord += barrel.xCoord;
		pos.yCoord += barrel.yCoord;
		pos.zCoord += barrel.zCoord;
		
		Vec3 delta = Vec3.createVectorHelper(tPos.xCoord - pos.xCoord, tPos.yCoord - pos.yCoord, tPos.zCoord - pos.zCoord);
		double targetYaw = -Math.atan2(delta.xCoord, delta.zCoord);
		
		double x = Math.sqrt(delta.xCoord * delta.xCoord + delta.zCoord * delta.zCoord);
		double y = delta.yCoord;
		double v0 = 20;
		double v02 = v0 * v0;
		double g = 9.81 * 0.05;
		double upperLower = mode == MODE_CANNON ? -1 : 1;
		double targetPitch = Math.atan((v02 + Math.sqrt(v02*v02 - g*(g*x*x + 2*y*v02)) * upperLower) / (g*x));
		
		this.turnTowardsAngle(targetPitch, targetYaw);
	}
	
	public int getShellLoaded() {
		
		for(int i = 1; i < 10; i++) {
			if(slots[i] != null) {
				if(slots[i].getItem() == ModItems.ammo_arty) {
					return slots[i].getItemDamage();
				}
			}
		}
		
		return -1;
	}
	
	public void conusmeAmmo(Item ammo) {
		
		for(int i = 1; i < 10; i++) {
			if(slots[i] != null && slots[i].getItem() == ammo) {
				this.decrStackSize(i, 1);
				return;
			}
		}
		
		this.markDirty();
	}

	public void spawnShell(int type) {
		
		Vec3 pos = this.getTurretPos();
		Vec3 vec = Vec3.createVectorHelper(this.getBarrelLength(), 0, 0);
		vec.rotateAroundZ((float) -this.rotationPitch);
		vec.rotateAroundY((float) -(this.rotationYaw + Math.PI * 0.5));
		
		EntityArtilleryShell proj = new EntityArtilleryShell(worldObj);
		proj.setPositionAndRotation(pos.xCoord + vec.xCoord, pos.yCoord + vec.yCoord, pos.zCoord + vec.zCoord, 0.0F, 0.0F);
		proj.setThrowableHeading(vec.xCoord, vec.yCoord, vec.zCoord, 20F, 0.0F);
		proj.setTarget((int) tPos.xCoord, (int) tPos.yCoord, (int) tPos.zCoord);
		proj.setType(type);
		
		if(this.mode != this.MODE_CANNON)
			proj.setWhistle(true);
		
		worldObj.spawnEntityInWorld(proj);
	}
	
	@Override
	public void updateEntity() {
		
		if(worldObj.isRemote) {
			this.lastBarrelPos = this.barrelPos;
			
			if(this.retracting) {
				this.barrelPos += 0.5;
				
				if(this.barrelPos >= 1) {
					this.retracting = false;
				}
				
			} else {
				this.barrelPos -= 0.05;
				if(this.barrelPos < 0) {
					this.barrelPos = 0;
				}
			}
		}
		
		if(this.mode == this.MODE_MANUAL) {
			if(!this.targetQueue.isEmpty()) {
				this.tPos = this.targetQueue.get(0);
			}
		} else {
			this.targetQueue.clear();
		}
		
		if(worldObj.isRemote) {
			this.lastRotationPitch = this.rotationPitch;
			this.lastRotationYaw = this.rotationYaw;
		}

		this.aligned = false;
		
		if(!worldObj.isRemote) {
			
			if(this.target != null && !target.isEntityAlive()) {
				this.target = null;
				this.stattrak++;
			}
		}
		
		if(target != null && this.mode != this.MODE_MANUAL) {
			if(!this.entityInLOS(this.target)) {
				this.target = null;
			}
		}
		
		if(!worldObj.isRemote) {
			
			if(target != null) {
				this.tPos = this.getEntityPos(target);
			} else {
				if(this.mode != this.MODE_MANUAL) {
					this.tPos = null;
				}
			}
		}
		
		if(isOn() && hasPower()) {
			
			if(tPos != null)
				this.alignTurret();
		} else {

			this.target = null;
			this.tPos = null;
		}
		
		if(!worldObj.isRemote) {
			
			if(this.target != null && !target.isEntityAlive()) {
				this.target = null;
				this.tPos = null;
				this.stattrak++;
			}
			
			if(isOn() && hasPower()) {
				searchTimer--;
				
				this.setPower(this.getPower() - this.getConsumption());
				
				if(searchTimer <= 0) {
					searchTimer = this.getDecetorInterval();
					
					if(this.target == null && this.mode != this.MODE_MANUAL)
						this.seekNewTarget();
				}
			} else {
				searchTimer = 0;
			}
			
			if(this.aligned) {
				this.updateFiringTick();
			}
			
			this.power = Library.chargeTEFromItems(slots, 10, this.power, this.getMaxPower());
			
			NBTTagCompound data = this.writePacket();
			this.networkPack(data, 250);
			
			this.didJustShoot = false;
			
		} else {
			
			Vec3 vec = Vec3.createVectorHelper(this.getBarrelLength(), 0, 0);
			vec.rotateAroundZ((float) -this.rotationPitch);
			vec.rotateAroundY((float) -(this.rotationYaw + Math.PI * 0.5));
			
			//this will fix the interpolation error when the turret crosses the 360° point
			if(Math.abs(this.lastRotationYaw - this.rotationYaw) > Math.PI) {
				
				if(this.lastRotationYaw < this.rotationYaw)
					this.lastRotationYaw += Math.PI * 2;
				else
					this.lastRotationYaw -= Math.PI * 2;
			}
		}
	}

	int timer;
	
	@Override
	public void updateFiringTick() {
		
		timer++;
		
		if(timer % 40 == 0) {
			
			int conf = this.getShellLoaded();
			
			if(conf != -1) {
				this.spawnShell(conf);
				this.conusmeAmmo(ModItems.ammo_arty);
				this.worldObj.playSoundEffect(xCoord, yCoord, zCoord, "hbm:turret.jeremy_fire", 25.0F, 1.0F);
				Vec3 pos = this.getTurretPos();
				Vec3 vec = Vec3.createVectorHelper(this.getBarrelLength(), 0, 0);
				vec.rotateAroundZ((float) -this.rotationPitch);
				vec.rotateAroundY((float) -(this.rotationYaw + Math.PI * 0.5));
				this.didJustShoot = true;
				
				NBTTagCompound data = new NBTTagCompound();
				data.setString("type", "vanillaExt");
				data.setString("mode", "largeexplode");
				data.setFloat("size", 0F);
				data.setByte("count", (byte)5);
				PacketDispatcher.wrapper.sendToAllAround(new AuxParticlePacketNT(data, pos.xCoord + vec.xCoord, pos.yCoord + vec.yCoord, pos.zCoord + vec.zCoord), new TargetPoint(worldObj.provider.dimensionId, xCoord, yCoord, zCoord, 150));
			}
			
			if(this.mode == this.MODE_MANUAL && !this.targetQueue.isEmpty()) {
				this.targetQueue.remove(0);
				this.tPos = null;
			}
		}
	}

	@Override
	public void handleButtonPacket(int value, int meta) {
		if(meta == 5) {
			this.mode++;
			if(this.mode > 2)
				this.mode = 0;
		} else{
			super.handleButtonPacket(value, meta);
		}
	}

	@Override
	protected NBTTagCompound writePacket() {
		NBTTagCompound data = super.writePacket();
		data.setShort("mode", mode);
		if(didJustShoot)
			data.setBoolean("didJustShoot", didJustShoot);
		return data;
	}

	@Override
	public void networkUnpack(NBTTagCompound nbt) {
		super.networkUnpack(nbt);
		this.mode = nbt.getShort("mode");
		if(nbt.getBoolean("didJustShoot"))
			this.retracting = true;
	}
	
	@Override
	public void readFromNBT(NBTTagCompound nbt) {
		super.readFromNBT(nbt);
		
		this.mode = nbt.getShort("mode");
	}
	
	@Override
	public void writeToNBT(NBTTagCompound nbt) {
		super.writeToNBT(nbt);
		
		nbt.setShort("mode", this.mode);
	}

	@Override
	public Container provideContainer(int ID, EntityPlayer player, World world, int x, int y, int z) {
		return new ContainerTurretBase(player.inventory, this);
	}

	@Override
	@SideOnly(Side.CLIENT)
	public GuiScreen provideGUI(int ID, EntityPlayer player, World world, int x, int y, int z) {
		return new GUITurretArty(player.inventory, this);
	}
}
