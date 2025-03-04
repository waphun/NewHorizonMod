package newhorizon.block.defence;

import arc.Core;
import arc.math.Mathf;
import arc.math.geom.Point2;
import arc.math.geom.Vec2;
import arc.scene.ui.layout.Table;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Time;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.Vars;
import mindustry.core.UI;
import mindustry.core.World;
import mindustry.entities.bullet.BulletType;
import mindustry.gen.*;
import mindustry.graphics.Drawf;
import mindustry.graphics.Pal;
import mindustry.logic.LAccess;
import mindustry.ui.Bar;
import mindustry.ui.Styles;
import mindustry.world.Tile;
import mindustry.world.meta.BlockStatus;
import mindustry.world.meta.Stat;
import mindustry.world.meta.StatUnit;
import mindustry.world.meta.StatValues;
import newhorizon.block.special.CommandableBlock;
import newhorizon.content.NHFx;
import newhorizon.content.NHSounds;
import newhorizon.func.DrawFunc;
import newhorizon.func.TableFunc;
import newhorizon.vars.NHVars;
import org.jetbrains.annotations.NotNull;

import static mindustry.Vars.tilesize;
import static mindustry.Vars.world;
import static newhorizon.func.TableFunc.LEN;

public abstract class CommandableAttackerBlock extends CommandableBlock{
	public float range = 100f;
	public float spread = 120f;
	public float prepareDelay = 60f;
	public int storage = 1;
	
	@NotNull protected BulletType bulletHitter;
	
	public CommandableAttackerBlock(String name){
		super(name);
		
		replaceable = true;
		canOverdrive = false;
		
		config(Integer.class, CommandableAttackerBlockBuild::commandAll);
		config(Point2.class, CommandableAttackerBlockBuild::setTarget);
	}
	
	@Override
	public void drawPlace(int x, int y, int rotation, boolean valid){
		super.drawPlace(x, y, rotation, valid);
		Drawf.dashCircle(x * tilesize + offset, y * tilesize + offset, range, Pal.accent);
	}
	
	@Override
	public void setStats(){
		super.setStats();
		stats.add(Stat.instructions, t -> t.add(Core.bundle.format("mod.ui.support-logic-control", "shootp", "\n 1 -> Control All\n 2 -> Control Single")));
		stats.add(Stat.range, range / tilesize, StatUnit.blocks);
		stats.add(Stat.damage, StatValues.ammo(ObjectMap.of(this, bulletHitter)));
	}
	
	@Override
	public void setBars() {
		super.setBars();
		bars.add("progress",
			(CommandableAttackerBlockBuild entity) -> new Bar(
				() -> Core.bundle.get("bar.progress"),
				() -> Pal.power,
				() -> (entity.reload % reloadTime) / reloadTime
			)
		);
		bars.add("storage",
			(CommandableAttackerBlockBuild entity) -> new Bar(
				() -> Core.bundle.format("bar.capacity", UI.formatAmount(entity.storaged())),
				() -> Pal.ammo,
				() -> (float)entity.storaged() / storage
			)
		);
	}
	
	public abstract class CommandableAttackerBlockBuild extends CommandableBlockBuild{
		public float countBack = prepareDelay;
		public boolean preparing = false;
		
		protected boolean attackGround = true;
		
		@Override
		public boolean isCharging(){return consValid() && reload < reloadTime * storage;}
		
		public int storaged(){return (int)(reload / reloadTime);}
		
		@Override
		public int getTarget(){
			return target;
		}
		
		@Override
		@NotNull
		public CommandableBlockType getType(){
			return CommandableBlockType.attacker;
		}
		
		@Override
		public float spread(){
			return spread;
		}
		
		@Override
		public void setTarget(Point2 p){
			NHVars.world.commandPos = target = p.pack();
			for(CommandableBlockBuild build : NHVars.world.commandables){
				if(build != null && build.team == team && groupBoolf.get(this, build)){
					build.overlap();
				}
			}
		}
		
		@Override
		public void control(LAccess type, Object p1, double p2, double p3, double p4){
			if(type == LAccess.shootp && timer.get(2, 10f) && (unit == null || !unit.isPlayer())){
				if(p1 instanceof Posc){
					Posc target = (Posc)p1;
					Vec2 velocity;
					if(target instanceof Velc)velocity = ((Velc)target).vel().cpy();
					else velocity = Vec2.ZERO;
					velocity.scl((delayTime(tmpPoint.set(World.toTile(velocity.x), World.toTile(velocity.y)).pack()) * Time.toSeconds + prepareDelay) / 1.5f).add(target);
					int pos = tmpPoint.set(World.toTile(velocity.x), World.toTile(velocity.y)).pack();
					if(Mathf.equal((float)p2,1))commandAll(pos);
					if(Mathf.equal((float)p2,2) && canCommand(pos) && !isPreparing())command(pos);
				}
			}
			
			super.control(type, p1, p2, p3, p4);
		}
		
		@Override
		public boolean isPreparing(){
			return preparing && countBack > 0;
		}
		
		@Override
		public void setPreparing(){
			preparing = true;
			countBack = prepareDelay;
		}
		
		@Override
		public BlockStatus status(){
			return canCommand(target) ? BlockStatus.active : isCharging() ? BlockStatus.noOutput : BlockStatus.noInput;
		}
		
		@Override
		public void updateTile(){
			super.updateTile();
			
			if(reload < reloadTime * storage && consValid()){
				reload += efficiency() * delta();
			}
			
			if(isPreparing()){
				countBack -= edelta();
			}
			
			if(preparing && countBack < 0){
				countBack = prepareDelay;
				preparing = false;
				shoot(lastTarget);
			}
		}
		
		@Override
		public boolean canCommand(int target){
			Tile tile = world.tile(target);
			return tile != null && consValid() && storaged() > 0 && within(tile, range);
		}
		
		@Override
		public boolean overlap(){
			target = NHVars.world.commandPos;
			return false;
		}
		
		@Override
		public void read(Reads read, byte revision){
			super.read(read, revision);
			target = read.i();
			reload = read.f();
			preparing = read.bool();
			countBack = read.f();
		}
		
		@Override
		public void write(Writes write){
			super.write(write);
			write.i(target);
			write.f(reload);
			write.bool(preparing);
			write.f(countBack);
		}
		
		@Override
		public void drawConfigure(){
			super.drawConfigure();
			tmpPoint.set(Point2.unpack(NHVars.world.commandPos));
			
			float realSpread = spread();
			
			Drawf.dashCircle(x, y, range, team.color);
			
			if(target < 0 && NHVars.world.commandPos < 0)return;
			
			Seq<CommandableBlockBuild> builds = new Seq<>();
			for(CommandableBlockBuild build : NHVars.world.commandables){
				if(build != this && build != null && build.team == team && groupBoolf.get(this, build) && build.canCommand(build.target)){
					builds.add(build);
					DrawFunc.posSquareLink(Pal.gray, 3, 4, false, build.x, build.y, World.unconv(tmpPoint.x), World.unconv(tmpPoint.y));
					realSpread = Math.max(realSpread, build.spread());
				}
			}
			
			for(CommandableBlockBuild build : builds){
				DrawFunc.posSquareLink(Pal.heal, 1, 2, false, build.x, build.y, World.unconv(tmpPoint.x), World.unconv(tmpPoint.y));
			}
			
			tmpPoint.set(Point2.unpack(NHVars.world.commandPos));
			
			if(NHVars.world.commandPos > 0){
				DrawFunc.posSquareLink(Pal.accent, 1, 2, true, x, y, World.unconv(tmpPoint.x), World.unconv(tmpPoint.y));
				DrawFunc.drawConnected(World.unconv(tmpPoint.x), World.unconv(tmpPoint.y), 10f, Pal.accent);
				Drawf.circles(World.unconv(tmpPoint.x), World.unconv(tmpPoint.y), realSpread, Pal.accent);
			}
			
			if(isValid())builds.add(this);
			for(CommandableBlockBuild build : builds){
				float time = build.delayTime(NHVars.world.commandPos);
				DrawFunc.overlayText("Delay: " + TableFunc.format(time) + " Sec.", build.x, build.y, build.block.size * tilesize / 2f, time > 4.5f ? Pal.accent : Pal.lancerLaser, true);
			}
			DrawFunc.overlayText(Core.bundle.format("mod.ui.participants", builds.size), World.unconv(tmpPoint.x), World.unconv(tmpPoint.y), tilesize * 2f, Pal.accent, true);
		}
		
		public void commandAll(Integer pos){
			tmpPoint.set(Point2.unpack(pos));
			float realSpread = 0f;
			
			Seq<CommandableBlockBuild> participants = new Seq<>();
			for(CommandableBlockBuild build : NHVars.world.commandables){
				if(build.team == team && groupBoolf.get(this, build) && build.canCommand(pos) && !build.isPreparing()){
					build.command(pos);
					participants.add(build);
					build.lastAccessed(Iconc.modeAttack + "");
					realSpread = Math.max(realSpread, build.spread());
				}
			}
			
			if(!Vars.headless && participants.size > 0){
				if(team != Vars.player.team()) TableFunc.showToast(Icon.warning, "[#ff7b69]Caution: []Attack " +  tmpPoint.x + ", " + tmpPoint.y, NHSounds.alarm);
				NHFx.attackWarningRange.at(World.unconv(tmpPoint.x), World.unconv(tmpPoint.y), realSpread, team.color);
				NHFx.spawn.at(World.unconv(tmpPoint.x), World.unconv(tmpPoint.y), realSpread, team.color);
			}
		}
		
		@Override
		public void command(Integer pos){
			setPreparing();
			NHFx.attackWarningPos.at(World.unconv(tmpPoint.x), World.unconv(tmpPoint.y), 0, team.color, tile);
			lastTarget = pos;
		}
		
		public abstract void shoot(Integer pos);
		
		@Override
		public void buildConfiguration(Table table){
			table.table(Tex.paneSolid, t -> {
				t.button(Icon.modeAttack, Styles.clearPartiali, () -> {
					configure(NHVars.world.commandPos);
				}).size(LEN).disabled(b -> NHVars.world.commandPos < 0);
				t.button("@mod.ui.select-target", Icon.move, Styles.cleart, LEN, () -> {
					TableFunc.pointSelectTable(t, this::configure);
				}).size(LEN * 4, LEN).row();
			}).fill();
			
		}
		
		@Override
		public float range(){
			return range;
		}
	}
	
	public abstract class AttackerEntity extends CommandEntity implements Damagec{}
}
