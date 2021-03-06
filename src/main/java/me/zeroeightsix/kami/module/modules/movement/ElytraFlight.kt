package me.zeroeightsix.kami.module.modules.movement

import me.zeroeightsix.kami.event.events.PacketEvent
import me.zeroeightsix.kami.event.events.PlayerTravelEvent
import me.zeroeightsix.kami.manager.mangers.PlayerPacketManager
import me.zeroeightsix.kami.module.Module
import me.zeroeightsix.kami.module.modules.player.LagNotifier
import me.zeroeightsix.kami.setting.Setting
import me.zeroeightsix.kami.setting.Setting.SettingListeners
import me.zeroeightsix.kami.setting.Settings
import me.zeroeightsix.kami.util.BlockUtils.checkForLiquid
import me.zeroeightsix.kami.util.BlockUtils.getGroundPosY
import me.zeroeightsix.kami.util.MovementUtils
import me.zeroeightsix.kami.util.astar.Astarpathfinder
import me.zeroeightsix.kami.util.event.listener
import me.zeroeightsix.kami.util.math.Vec2f
import me.zeroeightsix.kami.util.text.MessageSendHelper.sendChatMessage
import net.minecraft.client.audio.PositionedSoundRecord
import net.minecraft.init.Items
import net.minecraft.init.SoundEvents
import net.minecraft.network.play.client.CPacketEntityAction
import net.minecraft.network.play.client.CPacketPlayer
import net.minecraft.network.play.server.SPacketEntityMetadata
import net.minecraft.network.play.server.SPacketPlayerPosLook
import net.minecraft.network.play.server.SPacketUpdateHealth
import kotlin.math.*

@Module.Info(
        name = "ElytraFlight",
        description = "Allows infinite and way easier Elytra flying",
        category = Module.Category.MOVEMENT,
        modulePriority = 1000
)
object ElytraFlight : Module() {
    private val mode = register(Settings.enumBuilder(ElytraFlightMode::class.java).withName("Mode").withValue(ElytraFlightMode.CONTROL).build())
    private val pathfindMode = register(Settings.enumBuilder(PathfindMode::class.java).withName("Pathfind Mode").withValue(PathfindMode.CONTROL).build())
    private val page = register(Settings.e<Page>("Page", Page.GENERIC_SETTINGS))
    private val defaultSetting = register(Settings.b("Defaults", false))
    private val durabilityWarning = register(Settings.booleanBuilder("DurabilityWarning").withValue(true).withVisibility { page.value == Page.GENERIC_SETTINGS }.build())
    private val threshold = register(Settings.integerBuilder("Broken%").withRange(1, 50).withValue(5).withVisibility { durabilityWarning.value && page.value == Page.GENERIC_SETTINGS }.build())
    val autoLanding = register(Settings.booleanBuilder("AutoLanding").withValue(false).withVisibility { page.value == Page.GENERIC_SETTINGS }.build())

    /* Generic Settings */
    /* Takeoff */
    private val easyTakeOff = register(Settings.booleanBuilder("EasyTakeoff").withValue(true).withVisibility { page.value == Page.GENERIC_SETTINGS }.build())
    private val timerControl = register(Settings.booleanBuilder("TakeoffTimer").withValue(true).withVisibility { easyTakeOff.value && page.value == Page.GENERIC_SETTINGS }.build())
    private val highPingOptimize = register(Settings.booleanBuilder("HighPingOptimize").withValue(false).withVisibility { easyTakeOff.value && page.value == Page.GENERIC_SETTINGS }.build())
    private val minTakeoffHeight = register(Settings.floatBuilder("MinTakeoffHeight").withRange(0.0f, 1.5f).withValue(0.5f).withStep(0.1f).withVisibility { easyTakeOff.value && !highPingOptimize.value && page.value == Page.GENERIC_SETTINGS }.build())
    private val pathfindBonusHeight = register(Settings.booleanBuilder("PathfindBonusHeight").withValue(true).withVisibility { easyTakeOff.value && page.value == Page.GENERIC_SETTINGS }.build())

    /* Acceleration */
    private val accelerateStartSpeed = register(Settings.integerBuilder("StartSpeed").withRange(0, 100).withValue(100).withVisibility { effectiveMode != ElytraFlightMode.BOOST && page.value == Page.GENERIC_SETTINGS }.build())
    private val accelerateTime = register(Settings.floatBuilder("AccelerateTime").withRange(0.0f, 10.0f).withValue(0.0f).withVisibility { effectiveMode != ElytraFlightMode.BOOST && page.value == Page.GENERIC_SETTINGS }.build())
    private val autoReset = register(Settings.booleanBuilder("AutoReset").withValue(false).withVisibility { effectiveMode != ElytraFlightMode.BOOST && page.value == Page.GENERIC_SETTINGS }.build())

    /* Spoof Pitch */
    private val spoofPitch = register(Settings.booleanBuilder("SpoofPitch").withValue(true).withVisibility { effectiveMode != ElytraFlightMode.BOOST && page.value == Page.GENERIC_SETTINGS }.build())
    private val blockInteract = register(Settings.booleanBuilder("BlockInteract").withValue(false).withVisibility { spoofPitch.value && effectiveMode != ElytraFlightMode.BOOST && page.value == Page.GENERIC_SETTINGS }.build())
    private val forwardPitch = register(Settings.integerBuilder("ForwardPitch").withRange(-90, 90).withValue(0).withVisibility { spoofPitch.value && effectiveMode != ElytraFlightMode.BOOST && page.value == Page.GENERIC_SETTINGS }.build())

    /* Extra */
    val elytraSounds: Setting<Boolean> = register(Settings.booleanBuilder("ElytraSounds").withValue(true).withVisibility { page.value == Page.GENERIC_SETTINGS }.build())
    private val swingSpeed = register(Settings.floatBuilder("SwingSpeed").withValue(1.0f).withRange(0.0f, 2.0f).withVisibility { page.value == Page.GENERIC_SETTINGS && (effectiveMode == ElytraFlightMode.CONTROL || effectiveMode == ElytraFlightMode.PACKET) }.build())
    private val swingAmount = register(Settings.floatBuilder("SwingAmount").withValue(0.8f).withRange(0.0f, 2.0f).withVisibility { page.value == Page.GENERIC_SETTINGS && (effectiveMode == ElytraFlightMode.CONTROL || effectiveMode == ElytraFlightMode.PACKET) }.build())
    /* End of Generic Settings */

    /* Mode Settings */
    /* Boost */
    private val speedBoost = register(Settings.floatBuilder("SpeedB").withMinimum(0.0f).withValue(1.0f).withVisibility { effectiveMode == ElytraFlightMode.BOOST && page.value == Page.MODE_SETTINGS }.build())
    private val upSpeedBoost = register(Settings.floatBuilder("UpSpeedB").withMinimum(0.0f).withValue(1.0f).withMaximum(5.0f).withVisibility { effectiveMode == ElytraFlightMode.BOOST && page.value == Page.MODE_SETTINGS }.build())
    private val downSpeedBoost = register(Settings.floatBuilder("DownSpeedB").withMinimum(0.0f).withValue(1.0f).withMaximum(5.0f).withVisibility { effectiveMode == ElytraFlightMode.BOOST && page.value == Page.MODE_SETTINGS }.build())

    /* Control */
    private val boostPitchControl = register(Settings.integerBuilder("BaseBoostPitch").withRange(0, 90).withValue(20).withVisibility { effectiveMode == ElytraFlightMode.CONTROL && page.value == Page.MODE_SETTINGS }.build())
    private val ncpStrict = register(Settings.booleanBuilder("NCPStrict").withValue(true).withVisibility { effectiveMode == ElytraFlightMode.CONTROL && page.value == Page.MODE_SETTINGS }.build())
    private val legacyLookBoost = register(Settings.booleanBuilder("LegacyLookBoost").withValue(false).withVisibility { effectiveMode == ElytraFlightMode.CONTROL && page.value == Page.MODE_SETTINGS }.build())
    private val altitudeHoldControl = register(Settings.booleanBuilder("AutoControlAltitude").withValue(false).withVisibility { effectiveMode == ElytraFlightMode.CONTROL && page.value == Page.MODE_SETTINGS }.build())
    private val dynamicDownSpeed = register(Settings.booleanBuilder("DynamicDownSpeed").withValue(false).withVisibility { effectiveMode == ElytraFlightMode.CONTROL && page.value == Page.MODE_SETTINGS }.build())
    val speedControl = register(Settings.floatBuilder("SpeedC").withMinimum(0.0f).withValue(1.81f).withVisibility { effectiveMode == ElytraFlightMode.CONTROL && page.value == Page.MODE_SETTINGS }.build())
    private val fallSpeedControl = register(Settings.floatBuilder("FallSpeedC").withMinimum(0.0f).withMaximum(0.3f).withValue(0.00000000000003f).withStep(0.01f).withVisibility { effectiveMode == ElytraFlightMode.CONTROL && page.value == Page.MODE_SETTINGS }.build())
    private val downSpeedControl = register(Settings.floatBuilder("DownSpeedC").withMaximum(5.0f).withMinimum(0.0f).withValue(1.0f).withVisibility { effectiveMode == ElytraFlightMode.CONTROL && page.value == Page.MODE_SETTINGS }.build())
    private val fastDownSpeedControl = register(Settings.floatBuilder("DynamicDownSpeedC").withMaximum(5.0f).withMinimum(0.0f).withValue(2.0f).withVisibility { effectiveMode == ElytraFlightMode.CONTROL && dynamicDownSpeed.value && page.value == Page.MODE_SETTINGS }.build())

    /* Creative */
    private val speedCreative = register(Settings.floatBuilder("SpeedCR").withMinimum(0.0f).withValue(1.8f).withVisibility { effectiveMode == ElytraFlightMode.CREATIVE && page.value == Page.MODE_SETTINGS }.build())
    private val fallSpeedCreative = register(Settings.floatBuilder("FallSpeedCR").withMinimum(0.0f).withMaximum(0.3f).withValue(0.00001f).withVisibility { effectiveMode == ElytraFlightMode.CREATIVE && page.value == Page.MODE_SETTINGS }.build())
    private val upSpeedCreative = register(Settings.floatBuilder("UpSpeedCR").withMaximum(5.0f).withMinimum(0.0f).withValue(1.0f).withVisibility { effectiveMode == ElytraFlightMode.CREATIVE && page.value == Page.MODE_SETTINGS }.build())
    private val downSpeedCreative = register(Settings.floatBuilder("DownSpeedCR").withMaximum(5.0f).withMinimum(0.0f).withValue(1.0f).withVisibility { effectiveMode == ElytraFlightMode.CREATIVE && page.value == Page.MODE_SETTINGS }.build())

    /* Packet */
    private val speedPacket = register(Settings.floatBuilder("SpeedP").withMinimum(0.0f).withValue(1.8f).withVisibility { effectiveMode == ElytraFlightMode.PACKET && page.value == Page.MODE_SETTINGS }.build())
    private val fallSpeedPacket = register(Settings.floatBuilder("FallSpeedP").withMinimum(0.0f).withMaximum(0.3f).withValue(0.00001f).withVisibility { effectiveMode == ElytraFlightMode.PACKET && page.value == Page.MODE_SETTINGS }.build())
    private val downSpeedPacket = register(Settings.floatBuilder("DownSpeedP").withMinimum(0.0f).withMaximum(5.0f).withValue(1.0f).withVisibility { effectiveMode == ElytraFlightMode.PACKET && page.value == Page.MODE_SETTINGS }.build())
    /* End of Mode Settings */

    private enum class ElytraFlightMode {
        BOOST, CONTROL, CREATIVE, PACKET
    }

    private enum class PathfindMode {
        CONTROL, PACKET
    }

    private enum class Page {
        GENERIC_SETTINGS, MODE_SETTINGS
    }

    /* Pathfinding */
    private var effectiveMode = mode.value
    var pathfind = AutoWalk.mode.value == AutoWalk.AutoWalkMode.PATHFIND && AutoWalk.isEnabled

    /* Generic states */
    private var elytraIsEquipped = false
    private var elytraDurability = 0
    private var outOfDurability = false
    private var wasInLiquid = false
    var isFlying = false
    private var isPacketFlying = false
    private var isStandingStillH = false
    private var isStandingStill = false
    private var speedPercentage = 0.0f

    /* Control mode states */
    var hoverTarget = -1.0
    private var packetYaw = 0.0f
    private var packetPitch = 0.0f
    private var hoverState = false
    private var boostingTick = 0

    /* Event Listeners */
    init {
        listener<PacketEvent.Receive> {
            //if (mc.player == null || mc.player.isSpectator || !elytraIsEquipped || elytraDurability <= 1 || !isFlying || effectiveMode == ElytraFlightMode.BOOST) return@EventHook
            if (mc.player == null || mc.player.isSpectator || !elytraIsEquipped || elytraDurability <= 1) {
                return@listener
            } else {
                if (pathfind && (it.packet is SPacketEntityMetadata || it.packet is SPacketUpdateHealth ||
                                it.packet is SPacketEntityMetadata || it.packet is SPacketPlayerPosLook ||
                                it.packet is CPacketPlayer)) {
                    Astarpathfinder.onreceive(it)
                }
            }
            if (!isFlying || effectiveMode == ElytraFlightMode.BOOST) return@listener
            if (it.packet is SPacketPlayerPosLook && effectiveMode != ElytraFlightMode.PACKET) {
                val packet = it.packet
                packet.pitch = mc.player.rotationPitch
            }

            /* Cancels the elytra opening animation */
            if (it.packet is SPacketEntityMetadata && isPacketFlying) {
                val packet = it.packet
                if (packet.entityId == mc.player.getEntityId()) it.cancel()
            }
        }


        listener<PlayerTravelEvent> {
            if (mc.player == null || mc.player.isSpectator) return@listener
            stateUpdate(it)
            if (elytraIsEquipped && elytraDurability > 1) {

                if (pathfind) {
                    Astarpathfinder.update(it)
                    return@listener
                }
                if (autoLanding.value) {
                    landing(it)
                    return@listener
                }
                if (!isFlying && !isPacketFlying) {
                    takeoff(it)
                    spoofRotation()
                } else {
                    flyByMode(it)
                }

            } else if (!outOfDurability) {
                reset(true)
            }
        }
    }
    /* End of Event Listeners */

    /* Generic Functions */
    private fun stateUpdate(event: PlayerTravelEvent) {
        /* Elytra Check */
        val armorSlot = mc.player.inventory.armorInventory[2]
        elytraIsEquipped = armorSlot.getItem() == Items.ELYTRA

        /* Elytra Durability Check */
        if (elytraIsEquipped) {
            val oldDurability = elytraDurability
            elytraDurability = armorSlot.maxDamage - armorSlot.getItemDamage()

            /* Elytra Durability Warning, runs when player is in the air and durability changed */
            if (!mc.player.onGround && oldDurability != elytraDurability) {
                if (durabilityWarning.value && elytraDurability > 1 && elytraDurability < threshold.value * armorSlot.maxDamage / 100) {
                    mc.getSoundHandler().playSound(PositionedSoundRecord.getRecord(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f))
                    sendChatMessage("$chatName Warning: Elytra has " + (elytraDurability - 1) + " durability remaining")
                } else if (elytraDurability <= 1 && !outOfDurability) {
                    outOfDurability = true
                    if (durabilityWarning.value) {
                        mc.getSoundHandler().playSound(PositionedSoundRecord.getRecord(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f))
                        sendChatMessage("$chatName Elytra is out of durability, holding player in the air")
                    }
                }
            }
        } else elytraDurability = 0

        /* Holds player in the air if run out of durability */
        if (!mc.player.onGround && elytraDurability <= 1 && outOfDurability) {
            holdPlayer(event)
        } else if (outOfDurability) outOfDurability = false /* Reset if players is on ground or replace with a new elytra */

        /* wasInLiquid check */
        if (mc.player.inWater || mc.player.isInLava) {
            wasInLiquid = true
        } else if (mc.player.onGround || isFlying || isPacketFlying) {
            wasInLiquid = false
        }

        /* Elytra flying status check */
        isFlying = mc.player.isElytraFlying || (mc.player.capabilities.isFlying && effectiveMode == ElytraFlightMode.CREATIVE)

        /* Movement input check */
        isStandingStillH = (!pathfind || Astarpathfinder.holdinair) && (mc.player.movementInput.moveForward == 0f && mc.player.movementInput.moveStrafe == 0f)
        isStandingStill = isStandingStillH && !mc.player.movementInput.jump && !mc.player.movementInput.sneak

        /* Reset acceleration */
        if (!isFlying || isStandingStill) speedPercentage = accelerateStartSpeed.value.toFloat()

        /* Modify leg swing */
        if (shouldSwing()) {
            mc.player.prevLimbSwingAmount = mc.player.limbSwingAmount
            mc.player.limbSwing += swingSpeed.value
            val speedRatio = (MovementUtils.getSpeed() / getSettingSpeed()).toFloat()
            mc.player.limbSwingAmount += ((speedRatio * swingAmount.value) - mc.player.limbSwingAmount) * 0.4f
        }
    }

    private fun reset(cancelFlying: Boolean) {
        wasInLiquid = false
        isFlying = false
        isPacketFlying = false
        if (mc.player != null) {
            mc.timer.tickLength = 50.0f
            mc.player.capabilities.flySpeed = 0.05f
            if (cancelFlying) mc.player.capabilities.isFlying = false
        }
    }

    /* Holds player in the air */
    private fun holdPlayer(event: PlayerTravelEvent) {
        event.cancel()
        mc.timer.tickLength = 50.0f
        mc.player.setVelocity(0.0, -0.01, 0.0)
    }

    /* Auto landing */
    fun landing(event: PlayerTravelEvent) {
        when {
            mc.player.onGround -> {
                sendChatMessage("$chatName Landed!")
                autoLanding.value = false
                return
            }
            checkForLiquid() -> {
                sendChatMessage("$chatName Liquid below, disabling.")
                autoLanding.value = false
            }
            LagNotifier.paused -> {
                holdPlayer(event)
            }
            mc.player.capabilities.isFlying || !mc.player.isElytraFlying || isPacketFlying -> {
                reset(true)
                takeoff(event)
                return
            }
            else -> {
                when {
                    mc.player.posY > getGroundPosY(false) + 1.0 -> {
                        mc.timer.tickLength = 50.0f
                        mc.player.motionY = max(min(-(mc.player.posY - getGroundPosY(false)) / 20.0, -0.5), -5.0)
                    }
                    mc.player.motionY != 0.0 -> { /* Pause falling to reset fall distance */
                        if (!mc.integratedServerIsRunning) mc.timer.tickLength = 200.0f /* Use timer to pause longer */
                        mc.player.motionY = 0.0
                    }
                    else -> {
                        mc.player.motionY = -0.2
                    }
                }
            }
        }
        mc.player.setVelocity(0.0, mc.player.motionY, 0.0) /* Kills horizontal motion */
        event.cancel()
    }

    /* The best takeoff method <3 */
    fun takeoff(event: PlayerTravelEvent) {
        /* Pause Takeoff if server is lagging, player is in water/lava, or player is on ground */
        val timerSpeed = if (highPingOptimize.value) 400.0f else 200.0f
        val height = if (highPingOptimize.value) 0.0f else minTakeoffHeight.value
        val closeToGround = mc.player.posY <= getGroundPosY(false) + height && !wasInLiquid && !mc.integratedServerIsRunning
        if (!easyTakeOff.value || LagNotifier.paused || mc.player.onGround) {
            if (LagNotifier.paused && mc.player.posY - getGroundPosY(false) > 4.0f) holdPlayer(event) /* Holds player in the air if server is lagging and the distance is enough for taking fall damage */
            reset(mc.player.onGround)
            return
        }
        if (mc.player.motionY < 0 && !highPingOptimize.value || mc.player.motionY < -0.02) {
            if (closeToGround) {
                mc.timer.tickLength = 25.0f
                return
            }
            if (!highPingOptimize.value && !wasInLiquid && !mc.integratedServerIsRunning) { /* Cringe moment when you use elytra flight in single player world */
                event.cancel()
                mc.player.setVelocity(0.0, -0.02, 0.0)
            }
            if (timerControl.value && !mc.integratedServerIsRunning) mc.timer.tickLength = timerSpeed * 2.0f
            mc.connection!!.sendPacket(CPacketEntityAction(mc.player, CPacketEntityAction.Action.START_FALL_FLYING))
            hoverTarget = mc.player.posY + 0.2
            if (effectiveMode == ElytraFlightMode.PACKET && pathfind && pathfindBonusHeight.value) {
                //this seems to work on 2b2t.dev
                mc.player.motionY = 0.3 - (mc.player.posY - floor(mc.player.posY))
            }

        } else if (highPingOptimize.value && !closeToGround) {
            mc.timer.tickLength = timerSpeed
        }
        if (!pathfind)
            hoverTarget = mc.player.posY + 0.2
    }

    /**
     *  Calculate yaw for control and packet mode
     *
     *  @return Yaw in radians based on player rotation yaw and movement input
     */
    private fun getYaw(): Double {
        val yawRad = MovementUtils.calcMoveYaw()
        packetYaw = Math.toDegrees(yawRad).toFloat()
        return yawRad
    }

    /**
     * Calculate a speed with a non linear acceleration over time
     *
     * @return boostingSpeed if [boosting] is true, else return a accelerated speed.
     */
    private fun getSpeed(boosting: Boolean): Double {
        return when {
            boosting -> (if (ncpStrict.value) min(speedControl.value, 2.0f) else speedControl.value).toDouble()

            accelerateTime.value != 0.0f && accelerateStartSpeed.value != 100 -> {
                speedPercentage = when {
                    mc.gameSettings.keyBindSprint.isKeyDown -> 100.0f
                    autoReset.value && speedPercentage >= 100.0f -> accelerateStartSpeed.value.toFloat()
                    else -> min(speedPercentage + (100.0f - accelerateStartSpeed.value.toFloat()) / (accelerateTime.value * 20), 100.0f)
                }
                getSettingSpeed() * (speedPercentage / 100.0) * (cos((speedPercentage / 100.0) * PI) * -0.5 + 0.5)
            }

            else -> getSettingSpeed().toDouble()
        }
    }

    private fun getSettingSpeed(): Float {
        return when (effectiveMode) {
            ElytraFlightMode.BOOST -> speedBoost.value
            ElytraFlightMode.CONTROL -> speedControl.value
            ElytraFlightMode.CREATIVE -> speedCreative.value
            ElytraFlightMode.PACKET -> speedPacket.value
            else -> 0.0f
        }
    }

    private fun setSpeed(yaw: Double, boosting: Boolean) {
        val acceleratedSpeed = getSpeed(boosting)
        mc.player.setVelocity(sin(-yaw) * acceleratedSpeed, mc.player.motionY, cos(yaw) * acceleratedSpeed)
    }

    fun flyByMode(event: PlayerTravelEvent) {
        mc.timer.tickLength = 50.0f
        mc.player.isSprinting = false
        when (effectiveMode) {
            ElytraFlightMode.BOOST -> boostMode()
            ElytraFlightMode.CONTROL -> controlMode(event)
            ElytraFlightMode.CREATIVE -> creativeMode()
            ElytraFlightMode.PACKET -> packetMode(event)
        }
        spoofRotation()
    }
    /* End of Generic Functions */

    /* Boost mode */
    private fun boostMode() {
        val yaw = Math.toRadians(mc.player.rotationYaw.toDouble())
        mc.player.motionX -= mc.player.movementInput.moveForward * sin(yaw) * speedBoost.value / 20
        if (mc.player.movementInput.jump) mc.player.motionY += upSpeedBoost.value / 15 else if (mc.player.movementInput.sneak) mc.player.motionY -= downSpeedBoost.value / 15
        mc.player.motionZ += mc.player.movementInput.moveForward * cos(yaw) * speedBoost.value / 20
    }

    /* Control Mode */
    fun controlMode(event: PlayerTravelEvent) {
        /* States and movement input */
        val currentSpeed = sqrt(mc.player.motionX * mc.player.motionX + mc.player.motionZ * mc.player.motionZ)
        val moveUp = if (!legacyLookBoost.value) mc.player.movementInput.jump else mc.player.rotationPitch < -10.0f && !isStandingStillH
        val moveDown = if (InventoryMove.isEnabled && !InventoryMove.sneak.value && mc.currentScreen != null || moveUp) false else mc.player.movementInput.sneak
        val yaw: Double
        if (pathfind) {
            packetYaw = Astarpathfinder.currentyaw.toFloat()
            yaw = Math.toRadians(Astarpathfinder.currentyaw)
        } else {
            yaw = getYaw()
        }
        /* Dynamic down speed */
        val calcDownSpeed = if (dynamicDownSpeed.value) {
            val minDownSpeed = min(downSpeedControl.value, fastDownSpeedControl.value).toDouble()
            val maxDownSpeed = max(downSpeedControl.value, fastDownSpeedControl.value).toDouble()
            if (mc.player.rotationPitch > 0) {
                mc.player.rotationPitch / 90.0 * (maxDownSpeed - minDownSpeed) + minDownSpeed
            } else minDownSpeed
        } else downSpeedControl.value.toDouble()

        /* Hover */
        if (pathfind) {
            if (hoverTarget < 0.0 || moveUp) hoverTarget = floor(mc.player.posY) + 1.3 else if (moveDown) hoverTarget = floor(mc.player.posY) - 0.7
        } else {
            if (hoverTarget < 0.0 || moveUp) hoverTarget = mc.player.posY else if (moveDown) hoverTarget = mc.player.posY - calcDownSpeed
        }
        hoverState = (if (hoverState) mc.player.posY < hoverTarget else mc.player.posY < hoverTarget - 0.1) && (altitudeHoldControl.value || pathfind)

        /* Set velocity */
        if (!isStandingStillH || (moveUp && !pathfind)) {
            if ((moveUp || hoverState) && (currentSpeed >= 0.8 || mc.player.motionY > 1.0)) {
                upwardFlight(currentSpeed, yaw)
            } else if (!isStandingStillH || moveUp) { /* Runs when pressing wasd */
                packetPitch = forwardPitch.value.toFloat()
                mc.player.motionY = -fallSpeedControl.value.toDouble()
                setSpeed(yaw, moveUp)
                boostingTick = 0
            }
        } else mc.player.setVelocity(0.0, 0.0, 0.0) /* Stop moving if no inputs are pressed */
        if (moveDown) mc.player.motionY = -calcDownSpeed else if (pathfind && mc.player.posY >= hoverTarget) mc.player.motionY = -0.12/* Runs when holding shift */

        event.cancel()
    }

    private fun upwardFlight(currentSpeed: Double, yaw: Double) {
        val multipliedSpeed = 0.128 * min(speedControl.value, 2.0f)
        val strictPitch = Math.toDegrees(asin((multipliedSpeed - sqrt(multipliedSpeed * multipliedSpeed - 0.0348)) / 0.12)).toFloat()
        val basePitch = if (ncpStrict.value && strictPitch < boostPitchControl.value && !strictPitch.isNaN()) -strictPitch
        else -boostPitchControl.value.toFloat()
        val targetPitch = if (mc.player.rotationPitch < 0.0f) {
            max(mc.player.rotationPitch * (90.0f - boostPitchControl.value.toFloat()) / 90.0f - boostPitchControl.value.toFloat(), -90.0f)
        } else -boostPitchControl.value.toFloat()

        packetPitch = if (packetPitch <= basePitch && boostingTick > 2) {
            if (packetPitch < targetPitch) packetPitch += 17.0f
            if (packetPitch > targetPitch) packetPitch -= 17.0f
            max(packetPitch, targetPitch)
        } else basePitch
        boostingTick++

        /* These are actually the original Minecraft elytra fly code lol */
        val pitch = Math.toRadians(packetPitch.toDouble())
        val targetMotionX = sin(-yaw) * sin(-pitch)
        val targetMotionZ = cos(yaw) * sin(-pitch)
        val targetSpeed = sqrt(targetMotionX * targetMotionX + targetMotionZ * targetMotionZ)
        val upSpeed = currentSpeed * sin(-pitch) * 0.04
        val fallSpeed = cos(pitch) * cos(pitch) * 0.06 - 0.08

        mc.player.motionX -= upSpeed * targetMotionX / targetSpeed - (targetMotionX / targetSpeed * currentSpeed - mc.player.motionX) * 0.1
        mc.player.motionY += upSpeed * 3.2 + fallSpeed
        mc.player.motionZ -= upSpeed * targetMotionZ / targetSpeed - (targetMotionZ / targetSpeed * currentSpeed - mc.player.motionZ) * 0.1

        /* Passive motion loss */
        mc.player.motionX *= 0.99
        mc.player.motionY *= 0.98
        mc.player.motionZ *= 0.99
    }
    /* End of Control Mode */

    /* Creative Mode */
    private fun creativeMode() {
        if (mc.player.onGround) {
            reset(true)
            return
        }

        packetPitch = forwardPitch.value.toFloat()
        mc.player.capabilities.isFlying = true
        mc.player.capabilities.flySpeed = getSpeed(false).toFloat()

        val motionY = when {
            isStandingStill -> 0.0
            mc.player.movementInput.jump -> upSpeedCreative.value.toDouble()
            mc.player.movementInput.sneak -> -downSpeedCreative.value.toDouble()
            else -> -fallSpeedCreative.value.toDouble()
        }
        mc.player.setVelocity(0.0, motionY, 0.0) /* Remove the creative flight acceleration and set the motionY */
    }

    /* Packet Mode */
    private fun packetMode(event: PlayerTravelEvent) {
        isPacketFlying = !mc.player.onGround
        mc.player.connection.sendPacket(CPacketEntityAction(mc.player, CPacketEntityAction.Action.START_FALL_FLYING))
        var yaw = 0.0
        if (pathfind) {
            packetYaw = Astarpathfinder.currentyaw.toFloat()
            yaw = Math.toRadians(Astarpathfinder.currentyaw)
        } else {
            yaw = getYaw()
        }
        /* Set velocity */
        if (!isStandingStillH) { /* Runs when pressing wasd */
            setSpeed(yaw, false)
        } else mc.player.setVelocity(0.0, 0.0, 0.0)
        var moveDown = (mc.player.movementInput.sneak)


        if (pathfind) {
            if (mc.player.posY - floor(mc.player.posY) > .35) {
                moveDown = true
            } else if (mc.player.posY - floor(mc.player.posY) < .1) {
                Astarpathfinder.startlanding()
            }
        }
        mc.player.motionY = (if (moveDown) -downSpeedPacket.value else -fallSpeedPacket.value).toDouble()
        event.cancel()
    }

    fun shouldSwing(): Boolean {
        return isEnabled && isFlying && !autoLanding.value && (effectiveMode == ElytraFlightMode.CONTROL || effectiveMode == ElytraFlightMode.PACKET)
    }

    private fun spoofRotation() {
        if (mc.player.isSpectator || !elytraIsEquipped || elytraDurability <= 1 || !isFlying) return
        val packet = PlayerPacketManager.PlayerPacket(rotating = true, rotation = Vec2f(mc.player))
        if (autoLanding.value) {
            packet.rotation!!.y = -20f
        } else if (effectiveMode != ElytraFlightMode.BOOST) {
            if (!isStandingStill && effectiveMode != ElytraFlightMode.CREATIVE) packet.rotation!!.x = packetYaw
            if (spoofPitch.value) {
                if (!isStandingStill) packet.rotation!!.y = packetPitch

                /* Cancels rotation packets if player is not moving and not clicking */
                val cancelRotation = isStandingStill && ((!mc.gameSettings.keyBindUseItem.isKeyDown && !mc.gameSettings.keyBindAttack.isKeyDown && blockInteract.value) || !blockInteract.value)
                if (cancelRotation) {
                    packet.rotating = false
                }
            }
        }
        PlayerPacketManager.addPacket(this, packet)
    }

    override fun onDisable() {
        if (pathfind && mc.player != null) {
            Astarpathfinder.turnOff()
            AutoWalk.onEnable()
        }
        reset(true)
    }

    override fun onEnable() {
        AutoWalk.mode.settingListener = SettingListeners { it ->
            updatePathfindState()
        }
        updatePathfindState()
        autoLanding.value = false
        speedPercentage = accelerateStartSpeed.value.toFloat() /* For acceleration */
        hoverTarget = -1.0 /* For control mode */
    }

    fun updatePathfindState() {

        val autowalk = AutoWalk
        pathfind = autowalk.mode.value == AutoWalk.AutoWalkMode.PATHFIND && autowalk.isEnabled
        if (pathfind && !Astarpathfinder.enabled) {
            effectiveMode = when {
                pathfindMode.value == PathfindMode.CONTROL -> ElytraFlightMode.CONTROL
                pathfindMode.value == PathfindMode.PACKET -> ElytraFlightMode.PACKET
                else -> ElytraFlightMode.CONTROL
            }
            if (mc.player != null)
                Astarpathfinder.init()
        } else {
            effectiveMode = mode.value
            if (!pathfind && Astarpathfinder.enabled)
                if (mc.player != null)
                    Astarpathfinder.turnOff()
        }

    }

    private fun defaults() {
        mc.player?.let {
            durabilityWarning.value = true
            threshold.value = 5
            autoLanding.value = false

            easyTakeOff.value = true
            timerControl.value = true
            highPingOptimize.value = false
            minTakeoffHeight.value = 0.5f

            accelerateStartSpeed.value = 100
            accelerateTime.value = 0.0f
            autoReset.value = false

            spoofPitch.value = true
            blockInteract.value = false
            forwardPitch.value = 0

            elytraSounds.value = true
            swingSpeed.value = 1.0f
            swingAmount.value = 0.8f

            speedBoost.value = 1.0f
            upSpeedBoost.value = 1.0f
            downSpeedBoost.value = 1.0f

            boostPitchControl.value = 20
            ncpStrict.value = true
            legacyLookBoost.value = false
            altitudeHoldControl.value = false
            dynamicDownSpeed.value = false
            speedControl.value = 1.81f
            fallSpeedControl.value = 0.00000000000003f
            downSpeedControl.value = 1.0f
            fastDownSpeedControl.value = 2.0f

            speedCreative.value = 1.8f
            fallSpeedCreative.value = 0.00001f
            upSpeedCreative.value = 1.0f
            downSpeedCreative.value = 1.0f

            speedPacket.value = 1.8f
            fallSpeedPacket.value = 0.00001f
            downSpeedPacket.value = 1.0f
            pathfindBonusHeight.value = true
            pathfindMode.value = PathfindMode.CONTROL

            defaultSetting.value = false
            sendChatMessage("$chatName Set to defaults!")
        }
    }

    init {
        defaultSetting.settingListener = SettingListeners {
            if (defaultSetting.value) defaults()
        }

        /* Reset isFlying states when switching mode */
        mode.settingListener = SettingListeners {
            reset(true)
            updatePathfindState()
        }
    }
}
