package eu.pb4.sgui.mixin;

import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.GuiHelpers;
import eu.pb4.sgui.api.gui.AnvilInputGui;
import eu.pb4.sgui.api.gui.HotbarGui;
import eu.pb4.sgui.api.gui.SignGui;
import eu.pb4.sgui.api.gui.SimpleGui;
import eu.pb4.sgui.virtual.FakeScreenHandler;
import eu.pb4.sgui.virtual.VirtualScreenHandlerInterface;
import eu.pb4.sgui.virtual.book.BookScreenHandler;
import eu.pb4.sgui.virtual.hotbar.HotbarScreenHandler;
import eu.pb4.sgui.virtual.inventory.VirtualScreenHandler;
import eu.pb4.sgui.virtual.merchant.VirtualMerchantScreenHandler;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.LastSeenMessages;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.FilteredText;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Optional;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerPlayNetworkHandlerMixin {

    @Unique
    private AbstractContainerMenu sgui$previousScreen = null;

    @Shadow
    public ServerPlayer player;

    @Shadow
    public abstract void send(Packet<?> packet);

    @Inject(method = "handleContainerClick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;resetLastActionTime()V", shift = At.Shift.AFTER), cancellable = true)
    private void sgui$handleGuiClicks(ServerboundContainerClickPacket packet, CallbackInfo ci) {
        if (this.player.containerMenu instanceof VirtualScreenHandler handler) {
            try {
                var gui = handler.getGui();

                int slot = packet.getSlotNum();
                int button = packet.getButtonNum();
                ClickType type = ClickType.toClickType(packet.getClickType(), button, slot);
                boolean ignore = gui.onAnyClick(slot, type, packet.getClickType());
                if (ignore && !handler.getGui().getLockPlayerInventory() && (slot >= handler.getGui().getSize() || slot < 0 || handler.getGui().getSlotRedirect(slot) != null)) {
                    if (type == ClickType.MOUSE_DOUBLE_CLICK || (type.isDragging && type.value == 2)) {
                        GuiHelpers.sendPlayerScreenHandler(this.player);
                    }

                    return;
                }

                boolean allow = gui.click(slot, type, packet.getClickType());
                if (handler.getGui().isOpen()) {
                    if (!allow) {
                        if (slot >= 0 && slot < handler.getGui().getSize()) {
                            this.send(new ClientboundContainerSetSlotPacket(handler.containerId, handler.incrementStateId(), slot, handler.getSlot(slot).getItem()));
                        }
                        GuiHelpers.sendSlotUpdate(this.player, -1, -1, this.player.containerMenu.getCarried(), handler.getStateId());

                        if (type.numKey) {
                            int x = type.value + handler.slots.size() - 10;
                            GuiHelpers.sendSlotUpdate(player, handler.containerId, x, handler.getSlot(x).getItem(), handler.incrementStateId());
                        } else if (type == ClickType.MOUSE_DOUBLE_CLICK || type == ClickType.MOUSE_LEFT_SHIFT || type == ClickType.MOUSE_RIGHT_SHIFT || (type.isDragging && type.value == 2)) {
                            GuiHelpers.sendPlayerScreenHandler(this.player);
                        }
                    }
                }

            } catch (Throwable e) {
                handler.getGui().handleException(e);
                ci.cancel();
            }

            ci.cancel();
        } else if (this.player.containerMenu instanceof BookScreenHandler) {
            ci.cancel();
        }
    }

    @Inject(method = "handleContainerClick", at = @At("TAIL"))
    private void sgui$resyncGui(ServerboundContainerClickPacket packet, CallbackInfo ci) {
        if (this.player.containerMenu instanceof VirtualScreenHandler handler) {
            try {
                int slot = packet.getSlotNum();
                int button = packet.getButtonNum();
                ClickType type = ClickType.toClickType(packet.getClickType(), button, slot);

                if (type == ClickType.MOUSE_DOUBLE_CLICK || (type.isDragging && type.value == 2) || type.shift) {
                    GuiHelpers.sendPlayerScreenHandler(this.player);
                }

            } catch (Throwable e) {
                handler.getGui().handleException(e);
            }
        }
    }

    private static final String FORCE_THREAD = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V";

    @Inject(method = "handleContainerClose", at = @At(value = "INVOKE", target = FORCE_THREAD, shift = At.Shift.AFTER), cancellable = true)
    private void sgui$storeScreenHandler(ServerboundContainerClosePacket packet, CallbackInfo info) {
        if (this.player.containerMenu instanceof VirtualScreenHandlerInterface handler) {
            if (handler.getGui().canPlayerClose()) {
                this.sgui$previousScreen = this.player.containerMenu;
            } else {
                var screenHandler = this.player.containerMenu;
                if (screenHandler.getType() != null) {
                    try {
                        this.send(new ClientboundOpenScreenPacket(screenHandler.containerId, screenHandler.getType(), handler.getGui().getTitle()));
                        screenHandler.sendAllDataToRemote();
                    } catch (Throwable e) {

                    }
                }
                info.cancel();
            }

        }
    }

    @Inject(method = "handleContainerClose", at = @At("TAIL"))
    private void sgui$executeClosing(ServerboundContainerClosePacket packet, CallbackInfo info) {
        try {
            if (this.sgui$previousScreen != null) {
                if (this.sgui$previousScreen instanceof VirtualScreenHandlerInterface screenHandler) {
                    screenHandler.getGui().close(true);
                }
            }
        } catch (Throwable e) {
            if (this.sgui$previousScreen instanceof VirtualScreenHandlerInterface screenHandler) {
                screenHandler.getGui().handleException(e);
            } else {
                e.printStackTrace();
            }
        }
        this.sgui$previousScreen = null;
    }


    @Inject(method = "handleRenameItem", at = @At("TAIL"))
    private void sgui$catchRenamingWithCustomGui(ServerboundRenameItemPacket packet, CallbackInfo ci) {
        if (this.player.containerMenu instanceof VirtualScreenHandler handler) {
            try {
                if (handler.getGui() instanceof AnvilInputGui) {
                    ((AnvilInputGui) handler.getGui()).input(packet.getName());
                }
            } catch (Throwable e) {
                handler.getGui().handleException(e);
            }
        }
    }

    @Inject(method = "handlePlaceRecipe", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;resetLastActionTime()V", shift = At.Shift.BEFORE), cancellable = true)
    private void sgui$catchRecipeRequests(ServerboundPlaceRecipePacket packet, CallbackInfo ci) {
        if (this.player.containerMenu instanceof VirtualScreenHandler handler && handler.getGui() instanceof SimpleGui gui) {
            try {
                gui.onCraftRequest(packet.getRecipe(), packet.isShiftDown());
            } catch (Throwable e) {
                handler.getGui().handleException(e);
            }
        }
    }

    @Inject(method = "updateSignText", at = @At("HEAD"), cancellable = true)
    private void sgui$catchSignUpdate(ServerboundSignUpdatePacket packet, List<FilteredText> signText, CallbackInfo ci) {
        try {
            if (this.player.containerMenu instanceof FakeScreenHandler fake && fake.getGui() instanceof SignGui gui) {
                for (int i = 0; i < packet.getLines().length; i++) {
                    gui.setLineInternal(i, Component.literal(packet.getLines()[i]));
                }
                gui.close(true);
                ci.cancel();
            }
        } catch (Throwable e) {
            if (this.player.containerMenu instanceof VirtualScreenHandlerInterface handler ) {
                handler.getGui().handleException(e);
            } else {
                e.printStackTrace();
            }
        }
    }

    @Inject(method = "handleSelectTrade", at = @At(value = "INVOKE", shift = At.Shift.AFTER, target = FORCE_THREAD), cancellable = true)
    private void sgui$catchMerchantTradeSelect(ServerboundSelectTradePacket packet, CallbackInfo ci) {
        if (this.player.containerMenu instanceof VirtualMerchantScreenHandler merchantScreenHandler) {
            int id = packet.getItem();
            merchantScreenHandler.selectNewTrade(id);
            ci.cancel();
        }
    }

    @Inject(method = "handleSetCarriedItem", at = @At(value = "INVOKE", shift = At.Shift.AFTER, target = FORCE_THREAD), cancellable = true)
    private void sgui$catchUpdateSelectedSlot(ServerboundSetCarriedItemPacket packet, CallbackInfo ci) {
        if (this.player.containerMenu instanceof HotbarScreenHandler handler) {
            if (!handler.getGui().onSelectedSlotChange(packet.getSlot())) {
                this.send(new ClientboundSetCarriedItemPacket(handler.getGui().getSelectedSlot()));
            }
            ci.cancel();
        }
    }

    @Inject(method = "handleSetCreativeModeSlot", at = @At(value = "INVOKE", shift = At.Shift.AFTER, target = FORCE_THREAD), cancellable = true)
    private void sgui$cancelCreativeAction(ServerboundSetCreativeModeSlotPacket packet, CallbackInfo ci) {
        if (this.player.containerMenu instanceof VirtualScreenHandlerInterface) {
            ci.cancel();
        }
    }

    @Inject(method = "handleAnimate", at = @At(value = "INVOKE", shift = At.Shift.AFTER, target = FORCE_THREAD), cancellable = true)
    private void sgui$clickHandSwing(ServerboundSwingPacket packet, CallbackInfo ci) {
        if (this.player.containerMenu instanceof HotbarScreenHandler screenHandler) {
            var gui = screenHandler.getGui();
            if (!gui.onHandSwing()) {
                ci.cancel();
            }
        }
    }

    @Inject(method = "handleUseItem", at = @At(value = "INVOKE", shift = At.Shift.AFTER, target = FORCE_THREAD), cancellable = true)
    private void sgui$clickWithItem(ServerboundUseItemPacket packet, CallbackInfo ci) {
        if (this.player.containerMenu instanceof HotbarScreenHandler screenHandler) {
            var gui = screenHandler.getGui();
            if (screenHandler.slotsOld != null) {
                screenHandler.slotsOld.set(gui.getSelectedSlot() + 36, ItemStack.EMPTY);
                screenHandler.slotsOld.set(45, ItemStack.EMPTY);
            }
            gui.onClickItem();
            ci.cancel();
        }
    }

    @Inject(method = "handleUseItemOn", at = @At(value = "INVOKE", shift = At.Shift.AFTER, target = FORCE_THREAD), cancellable = true)
    private void sgui$clickOnBlock(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
        if (this.player.containerMenu instanceof HotbarScreenHandler screenHandler) {
            var gui = screenHandler.getGui();

            if (!gui.onClickBlock(packet.getHitResult())) {
                var pos = packet.getHitResult().getBlockPos();
                if (screenHandler.slotsOld != null) {
                    screenHandler.slotsOld.set(gui.getSelectedSlot() + 36, ItemStack.EMPTY);
                    screenHandler.slotsOld.set(45, ItemStack.EMPTY);
                }

                this.send(new ClientboundBlockUpdatePacket(pos, this.player. serverLevel().getBlockState(pos)));
                pos = pos.relative(packet.getHitResult().getDirection());
                this.send(new ClientboundBlockUpdatePacket(pos, this.player.serverLevel().getBlockState(pos)));
                this.send(new ClientboundBlockChangedAckPacket(packet.getSequence()));

                ci.cancel();
            }
        }
    }

    @Inject(method = "handlePlayerAction", at = @At(value = "INVOKE", shift = At.Shift.AFTER, target = FORCE_THREAD), cancellable = true)
    private void sgui$onPlayerAction(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
        if (this.player.containerMenu instanceof HotbarScreenHandler screenHandler) {
            var gui = screenHandler.getGui();

            if (!gui.onPlayerAction(packet.getAction(), packet.getDirection())) {
                var pos = packet.getPos();
                if (screenHandler.slotsOld != null) {
                    screenHandler.slotsOld.set(gui.getSelectedSlot() + 36, ItemStack.EMPTY);
                    screenHandler.slotsOld.set(45, ItemStack.EMPTY);
                }
                this.send(new ClientboundBlockUpdatePacket(pos, this.player.serverLevel().getBlockState(pos)));
                pos = pos.relative(packet.getDirection());
                this.send(new ClientboundBlockUpdatePacket(pos, this.player.serverLevel().getBlockState(pos)));
                this.send(new ClientboundBlockChangedAckPacket(packet.getSequence()));
                ci.cancel();
            }
        }
    }

    @Inject(method = "handleInteract", at = @At(value = "INVOKE", shift = At.Shift.AFTER, target = FORCE_THREAD), cancellable = true)
    private void sgui$clickOnEntity(ServerboundInteractPacket packet, CallbackInfo ci) {
        if (this.player.containerMenu instanceof HotbarScreenHandler screenHandler) {
            var gui = screenHandler.getGui();
            var buf = new FriendlyByteBuf(Unpooled.buffer());
            packet.write(buf);

            int entityId = buf.readVarInt();
            var type = buf.readEnum(HotbarGui.EntityInteraction.class);

            Vec3 interactionPos = null;

            switch (type) {
                case INTERACT:
                    buf.readVarInt();
                    break;
                case INTERACT_AT:
                    interactionPos = new Vec3(buf.readFloat(), buf.readFloat(), buf.readFloat());
                    buf.readVarInt();
            }

            var isSneaking = buf.readBoolean();

            if (!gui.onClickEntity(entityId, type, isSneaking, interactionPos)) {
                if (screenHandler.slotsOld != null) {
                    screenHandler.slotsOld.set(gui.getSelectedSlot() + 36, ItemStack.EMPTY);
                    screenHandler.slotsOld.set(45, ItemStack.EMPTY);
                }
                ci.cancel();
            }
        }
    }

    @Inject(method = "method_44900", at = @At("HEAD"), cancellable = true)
    private void sgui$onMessage(ServerboundChatPacket packet, Optional<LastSeenMessages> optional, CallbackInfo ci) {
        if (this.player.containerMenu instanceof BookScreenHandler handler) {
            try {
                if (handler.getGui().onCommand(packet.message())) {
                    ci.cancel();
                }
            } catch (Throwable e) {
                handler.getGui().handleException(e);
            }
        }
    }

    @Inject(method = "method_44356", at = @At("HEAD"), cancellable = true)
    private void sgui$onCommand(ServerboundChatCommandPacket packet, Optional<LastSeenMessages> optional, CallbackInfo ci) {
        if (this.player.containerMenu instanceof BookScreenHandler handler) {
            try {
                if (handler.getGui().onCommand("/" + packet.command())) {
                    ci.cancel();
                }
            } catch (Throwable e) {
                handler.getGui().handleException(e);
            }
        }
    }
}
