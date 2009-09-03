package com.intellij.conversion.impl;

import com.intellij.conversion.RunManagerSettings;
import com.intellij.conversion.CannotConvertException;
import com.intellij.ide.impl.convert.JDomConvertingUtil;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * @author nik
 */
public class RunManagerSettingsImpl implements RunManagerSettings {
  @NonNls public static final String RUN_MANAGER_COMPONENT_NAME = "RunManager";
  @NonNls private static final String PROJECT_RUN_MANAGER = "ProjectRunConfigurationManager";
  @NonNls public static final String CONFIGURATION_ELEMENT = "configuration";
  private SettingsXmlFile myWorkspaceFile;
  private SettingsXmlFile myProjectFile;
  private List<SettingsXmlFile> mySharedConfigurationFiles;

  public RunManagerSettingsImpl(@NotNull File workspaceFile, @Nullable File projectFile, @Nullable File[] sharedConfigurationFiles,
                                ConversionContextImpl context) throws CannotConvertException {
    if (workspaceFile.exists()) {
      myWorkspaceFile = context.getOrCreateFile(workspaceFile);
    }

    if (projectFile != null && projectFile.exists()) {
      myProjectFile = context.getOrCreateFile(projectFile);
    }

    mySharedConfigurationFiles = new ArrayList<SettingsXmlFile>();
    if (sharedConfigurationFiles != null) {
      for (File file : sharedConfigurationFiles) {
        mySharedConfigurationFiles.add(context.getOrCreateFile(file));
      }
    }
  }

  @NotNull
  public Collection<? extends Element> getRunConfigurations() {
    final List<Element> result = new ArrayList<Element>();
    if (myWorkspaceFile != null) {
      result.addAll(JDomConvertingUtil.getChildren(myWorkspaceFile.findComponent(RUN_MANAGER_COMPONENT_NAME), CONFIGURATION_ELEMENT));
    }

    if (myProjectFile != null) {
      result.addAll(JDomConvertingUtil.getChildren(myProjectFile.findComponent(PROJECT_RUN_MANAGER), CONFIGURATION_ELEMENT));
    }

    for (SettingsXmlFile file : mySharedConfigurationFiles) {
      result.addAll(JDomConvertingUtil.getChildren(file.getRootElement(), CONFIGURATION_ELEMENT));
    }

    return result;
  }

  public Collection<File> getAffectedFiles() {
    final List<File> files = new ArrayList<File>();
    if (myWorkspaceFile != null) {
      files.add(myWorkspaceFile.getFile());
    }
    if (myProjectFile != null) {
      files.add(myProjectFile.getFile());
    }
    for (SettingsXmlFile file : mySharedConfigurationFiles) {
      files.add(file.getFile());
    }
    return files;
  }

  public void save() throws IOException {
    if (myWorkspaceFile != null) {
      myWorkspaceFile.save();
    }
    if (myProjectFile != null) {
      myProjectFile.save();
    }
    for (SettingsXmlFile file : mySharedConfigurationFiles) {
      file.save();
    }
  }
}
