/*
 * This file is part of Applied Energistics 2. Copyright (c) 2013 - 2015, AlgorithmX2, All rights reserved. Applied
 * Energistics 2 is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version. Applied Energistics 2 is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General
 * Public License for more details. You should have received a copy of the GNU Lesser General Public License along with
 * Applied Energistics 2. If not, see <http://www.gnu.org/licenses/lgpl>.
 */

package appeng.core;

import java.io.File;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nonnull;

import net.minecraftforge.common.config.Configuration;

import com.google.common.base.Stopwatch;

import appeng.core.crash.CrashInfo;
import appeng.core.crash.IntegrationCrashEnhancement;
import appeng.core.crash.ModCrashEnhancement;
import appeng.core.features.AEFeature;
import appeng.core.sync.GuiBridge;
import appeng.core.sync.network.NetworkHandler;
import appeng.core.worlddata.WorldData;
import appeng.hooks.TickHandler;
import appeng.integration.IntegrationRegistry;
import appeng.recipes.CustomRecipeConfig;
import appeng.recipes.CustomRecipeForgeConfiguration;
import appeng.server.AECommand;
import appeng.services.export.ExportConfig;
import appeng.services.export.ExportProcess;
import appeng.services.export.ForgeExportConfig;
import appeng.util.InvTweakSortingModule;
import appeng.util.Platform;
import baubles.api.expanded.BaubleExpandedSlots;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.Mod.EventHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLInterModComms;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerAboutToStartEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppedEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import cpw.mods.fml.common.network.NetworkRegistry;

@Mod(
        modid = AppEng.MOD_ID,
        acceptedMinecraftVersions = "[1.7.10]",
        name = AppEng.MOD_NAME,
        version = AEConfig.VERSION,
        dependencies = AppEng.MOD_DEPENDENCIES,
        guiFactory = "appeng.client.gui.config.AEConfigGuiFactory")
public final class AppEng {

    public static final String MOD_ID = "appliedenergistics2";
    public static final String MOD_NAME = "Applied Energistics 2";
    public static final String MOD_DEPENDENCIES =
            // a few mods, AE should load after, probably.
            // required-after:AppliedEnergistics2API|all;
            // "after:gregtech_addon;after:Mekanism;after:IC2;after:ThermalExpansion;after:BuildCraft|Core;" +

            // depend on version of forge used for build.
            "after:appliedenergistics2-core;" + "required-after:Forge@[" // require forge.
                    + net.minecraftforge.common.ForgeVersion.majorVersion
                    + '.' // majorVersion
                    + net.minecraftforge.common.ForgeVersion.minorVersion
                    + '.' // minorVersion
                    + net.minecraftforge.common.ForgeVersion.revisionVersion
                    + '.' // revisionVersion
                    + net.minecraftforge.common.ForgeVersion.buildVersion
                    + ",);" // buildVersion
                    + "required-after:gtnhlib@[0.6.11,)"; // require gtnhlib

    @Nonnull
    private static final AppEng INSTANCE = new AppEng();

    private final Registration registration;

    private File configDirectory;
    private CustomRecipeConfig customRecipeConfig;
    public static final String BAUBLESLOT = "Terminal";

    /**
     * Folder for recipes
     * <p>
     * used for CSV item names and the recipes
     */
    private File recipeDirectory;

    /**
     * determined in pre-init but used in init
     */
    private ExportConfig exportConfig;

    AppEng() {
        FMLCommonHandler.instance().registerCrashCallable(new ModCrashEnhancement(CrashInfo.MOD_VERSION));

        this.registration = new Registration();
    }

    @Nonnull
    @Mod.InstanceFactory
    public static AppEng instance() {
        return INSTANCE;
    }

    @Nonnull
    public final Registration getRegistration() {
        return this.registration;
    }

    @EventHandler
    private void preInit(final FMLPreInitializationEvent event) {
        if (!Loader.isModLoaded("appliedenergistics2-core")) {
            CommonHelper.proxy.missingCoreMod();
        }

        if (Loader.isModLoaded("Baubles|Expanded")) {
            BaubleExpandedSlots.tryRegisterType(BAUBLESLOT);
            BaubleExpandedSlots.tryAssignSlotOfType(BAUBLESLOT);
        }

        final Stopwatch watch = Stopwatch.createStarted();
        this.configDirectory = new File(event.getModConfigurationDirectory().getPath(), "AppliedEnergistics2");
        this.recipeDirectory = new File(this.configDirectory, "recipes");

        final File configFile = new File(this.configDirectory, "AppliedEnergistics2.cfg");
        final File facadeFile = new File(this.configDirectory, "Facades.cfg");
        final File recipeFile = new File(this.configDirectory, "CustomRecipes.cfg");
        final Configuration recipeConfiguration = new Configuration(recipeFile);

        AEConfig.instance = new AEConfig(configFile);
        FacadeConfig.instance = new FacadeConfig(facadeFile);
        this.customRecipeConfig = new CustomRecipeForgeConfiguration(recipeConfiguration);
        this.exportConfig = new ForgeExportConfig(recipeConfiguration);

        AELog.info("Pre Initialization ( started )");

        CreativeTab.init();
        if (AEConfig.instance.isFeatureEnabled(AEFeature.Facades)) {
            CreativeTabFacade.init();
        }

        if (Platform.isClient()) {
            CommonHelper.proxy.init();
        }

        this.registration.preInitialize(event);

        AELog.info("Pre Initialization ( ended after " + watch.elapsed(TimeUnit.MILLISECONDS) + "ms )");
    }

    private void startService(final String serviceName, final Thread thread) {
        thread.setName(serviceName);
        thread.setPriority(Thread.MIN_PRIORITY);

        AELog.info("Starting " + serviceName);
        thread.start();
    }

    @EventHandler
    private void init(final FMLInitializationEvent event) {
        final Stopwatch start = Stopwatch.createStarted();
        AELog.info("Initialization ( started )");

        if (this.exportConfig.isExportingItemNamesEnabled()) {
            final ExportProcess process = new ExportProcess(this.recipeDirectory, this.exportConfig);
            final Thread exportProcessThread = new Thread(process);

            this.startService("AE2 CSV Export", exportProcessThread);
        }

        if (Platform.isClient()) InvTweakSortingModule.init();

        this.registration.initialize(event, this.recipeDirectory, this.customRecipeConfig);
        IntegrationRegistry.INSTANCE.init();

        AELog.info("Initialization ( ended after " + start.elapsed(TimeUnit.MILLISECONDS) + "ms )");
    }

    @EventHandler
    private void postInit(final FMLPostInitializationEvent event) {
        final Stopwatch start = Stopwatch.createStarted();
        AELog.info("Post Initialization ( started )");

        this.registration.postInit(event);
        IntegrationRegistry.INSTANCE.postInit();
        FMLCommonHandler.instance().registerCrashCallable(new IntegrationCrashEnhancement());

        CommonHelper.proxy.postInit();
        AEConfig.instance.save();

        NetworkRegistry.INSTANCE.registerGuiHandler(this, GuiBridge.GUI_Handler);
        NetworkHandler.instance = new NetworkHandler("AE2");

        AELog.info("Post Initialization ( ended after " + start.elapsed(TimeUnit.MILLISECONDS) + "ms )");
    }

    @EventHandler
    private void handleIMCEvent(final FMLInterModComms.IMCEvent event) {
        final IMCHandler imcHandler = new IMCHandler();

        imcHandler.handleIMCEvent(event);
    }

    @EventHandler
    private void serverAboutToStart(final FMLServerAboutToStartEvent evt) {
        WorldData.onServerAboutToStart();
    }

    @EventHandler
    private void serverStopping(final FMLServerStoppingEvent event) {
        WorldData.instance().onServerStopping();
    }

    @EventHandler
    private void serverStopped(final FMLServerStoppedEvent event) {
        if (WorldData.instance() != null) WorldData.instance().onServerStoppped();
        TickHandler.INSTANCE.shutdown();
    }

    @EventHandler
    private void serverStarting(final FMLServerStartingEvent evt) {
        evt.registerServerCommand(new AECommand(evt.getServer()));
    }
}
