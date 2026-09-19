package br.net.fabiozumbi12.RedProtect.Bukkit.listeners;

import br.net.fabiozumbi12.RedProtect.Bukkit.RedProtect;
import br.net.fabiozumbi12.RedProtect.Bukkit.Region;
import br.net.fabiozumbi12.RedProtect.Core.config.Category.MainCategory;
import io.papermc.paper.event.player.PlayerOpenSignEvent;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.entity.Player;
import org.bukkit.event.block.SignChangeEvent;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class PrivateSignEditTest {
    private MockedStatic<RedProtect> singleton;
    private RedProtect plugin;
    private MainCategory config;
    private BlockListener listener;
    private Block block;
    private Sign sign;
    private SignSide front;
    private Player editor;
    private String[] saved;

    @Before
    public void setUp() throws Exception {
        plugin = mock(RedProtect.class, RETURNS_DEEP_STUBS);
        singleton = mockStatic(RedProtect.class);
        singleton.when(RedProtect::get).thenReturn(plugin);
        Field logger = RedProtect.class.getField("logger");
        logger.setAccessible(true);
        logger.set(plugin, mock(logger.getType()));
        config = new MainCategory();
        config.private_cat.use = true;
        config.private_cat.allow_outside = true;
        config.private_cat.allowed_blocks.add("CHEST");
        config.server_protection.sign_spy.enabled = false;
        when(plugin.getConfigManager().configRoot()).thenReturn(config);
        when(plugin.getLanguageManager().get("blocklistener.container.signline")).thenReturn("[private]");
        when(plugin.getLanguageManager().get("blocklistener.container.signline.public")).thenReturn("[public]");
        when(plugin.getRegionManager().getTopRegion(any())).thenReturn(null);
        editor = mock(Player.class);
        when(editor.getName()).thenReturn("Visitor");
        when(plugin.getUtil().isRealPlayer(editor)).thenReturn(true);
        block = mock(Block.class);
        sign = mock(Sign.class);
        front = mock(SignSide.class);
        saved = new String[]{"[private]", "ChestOwner", "TrustedGuest", ""};
        when(block.getType()).thenReturn(Material.OAK_WALL_SIGN);
        when(block.getState()).thenReturn(sign);
        when(sign.getBlock()).thenReturn(block);
        when(sign.getSide(Side.FRONT)).thenReturn(front);
        when(front.getLines()).thenAnswer(invocation -> saved.clone());
        when(front.getLine(anyInt())).thenAnswer(invocation -> saved[invocation.getArgument(0)]);
        Block air = mock(Block.class);
        when(block.getRelative(any(BlockFace.class))).thenReturn(air);
        when(block.getRelative(BlockFace.SELF)).thenReturn(block);
        Block chest = mock(Block.class);
        when(chest.getType()).thenReturn(Material.CHEST);
        when(plugin.getVersionHelper().getBlockRelative(block)).thenReturn(chest);
        listener = new BlockListener();
    }

    @After
    public void tearDown() {
        if (singleton != null) singleton.close();
    }

    private SignChangeEvent edit(Side side, String header, String owner) {
        SignChangeEvent event = new SignChangeEvent(block, editor, new String[]{header, owner, "", ""}, side);
        listener.onSignPlace(event);
        return event;
    }

    @Test
    public void nonOwnerCannotRemoveOrRewriteLockOnEitherSide() {
        for (Side side : Side.values()) {
            for (String header : new String[]{"", "ordinary text", "[private]", "[public]"}) {
                assertTrue(edit(side, header, "Visitor").isCancelled());
            }
        }
        verify(block, never()).breakNaturally();
    }

    @Test
    public void trustedChestUserCannotEditOwnerSign() {
        when(editor.getName()).thenReturn("TrustedGuest");
        assertTrue(edit(Side.FRONT, "[private]", "TrustedGuest").isCancelled());
    }

    @Test
    public void regionSignPermissionDoesNotOverridePrivateOwner() {
        Region region = mock(Region.class);
        when(plugin.getRegionManager().getTopRegion(any())).thenReturn(region);
        when(region.canSign(editor)).thenReturn(true);
        config.private_cat.allow_outside = false;
        assertTrue(edit(Side.FRONT, "ordinary text", "").isCancelled());
    }

    @Test
    public void editorOpeningIsDeniedForEveryCauseAndSide() {
        for (PlayerOpenSignEvent.Cause cause : PlayerOpenSignEvent.Cause.values()) {
            for (Side side : Side.values()) {
                PlayerOpenSignEvent event = new PlayerOpenSignEvent(editor, sign, side, cause);
                listener.onSignOpen(event);
                assertTrue(event.isCancelled());
            }
        }
    }

    @Test
    public void ownerCanEditWithoutAccidentallyTransferringOwnership() {
        when(editor.getName()).thenReturn("ChestOwner");
        SignChangeEvent event = edit(Side.FRONT, "[private]", "Visitor");
        assertFalse(event.isCancelled());
        assertEquals("ChestOwner", event.getLine(1));
        verify(block, never()).breakNaturally();
    }

    @Test
    public void ownerCanRemoveLock() {
        when(editor.getName()).thenReturn("ChestOwner");
        SignChangeEvent event = edit(Side.FRONT, "ordinary text", "");
        assertFalse(event.isCancelled());
        assertEquals("ordinary text", event.getLine(0));
    }

    @Test
    public void ownerCanEditBackWithoutCreatingAnotherLockOrBreakingSign() {
        when(editor.getName()).thenReturn("ChestOwner");
        SignChangeEvent event = edit(Side.BACK, "[public]", "BackText");
        assertFalse(event.isCancelled());
        assertEquals("BackText", event.getLine(1));
        verify(block, never()).breakNaturally();
    }

    @Test
    public void bypassCanEditButKeepsOriginalOwner() {
        when(editor.hasPermission("redprotect.bypass")).thenReturn(true);
        SignChangeEvent event = edit(Side.FRONT, "[private]", "Visitor");
        assertFalse(event.isCancelled());
        assertEquals("ChestOwner", event.getLine(1));
    }

    @Test
    public void privateBypassCanOpenEditor() {
        when(plugin.getPermissionHandler().hasPermOrBypass(editor, "redprotect.bypass.private")).thenReturn(true);
        PlayerOpenSignEvent event = new PlayerOpenSignEvent(editor, sign, Side.FRONT, PlayerOpenSignEvent.Cause.values()[0]);
        listener.onSignOpen(event);
        assertFalse(event.isCancelled());
    }

    @Test
    public void publicSignsAlsoRequireOwnerToEdit() {
        saved[0] = "[public]";
        assertTrue(edit(Side.FRONT, "[private]", "Visitor").isCancelled());
    }

    @Test
    public void bypassConvertingPrivateToPublicKeepsOriginalOwner() {
        when(editor.hasPermission("redprotect.bypass")).thenReturn(true);
        SignChangeEvent event = edit(Side.FRONT, "[public]", "Visitor");
        assertFalse(event.isCancelled());
        assertEquals("ChestOwner", event.getLine(1));
        verify(block, never()).breakNaturally();
    }

    @Test
    public void newFrontLockGetsCreatorAsOwner() {
        saved = new String[]{"", "", "", ""};
        SignChangeEvent event = edit(Side.FRONT, "[private]", "SomeoneElse");
        assertFalse(event.isCancelled());
        assertEquals("Visitor", event.getLine(1));
    }

    @Test
    public void ordinarySignsStayEditable() {
        saved = new String[]{"Directions", "", "", ""};
        assertFalse(edit(Side.FRONT, "New directions", "").isCancelled());
    }

    @Test
    public void disabledPrivateProtectionDoesNotBlockEditing() {
        config.private_cat.use = false;
        assertFalse(edit(Side.FRONT, "ordinary text", "").isCancelled());
    }

    @Test
    public void namelessLockIsNotClaimedThroughEditing() {
        saved[1] = "";
        assertTrue(edit(Side.FRONT, "[private]", "Visitor").isCancelled());
    }
}
