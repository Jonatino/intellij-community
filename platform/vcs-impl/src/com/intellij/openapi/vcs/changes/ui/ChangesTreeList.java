/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.openapi.vcs.changes.ui;

import com.intellij.icons.AllIcons;
import com.intellij.ide.CopyProvider;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.ide.util.treeView.TreeState;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diff.DiffBundle;
import com.intellij.openapi.keymap.KeymapManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.EmptyRunnable;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsBundle;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.changes.issueLinks.TreeLinkMouseListener;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.*;
import com.intellij.ui.components.panels.NonOpaquePanel;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.ui.treeStructure.actions.CollapseAllAction;
import com.intellij.ui.treeStructure.actions.ExpandAllAction;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.Convertor;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.ui.tree.TreeUtil;
import com.intellij.util.ui.tree.WideSelectionTreeUI;
import org.intellij.lang.annotations.JdkConstants;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.plaf.basic.BasicTreeUI;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;

/**
 * @author max
 */
public abstract class ChangesTreeList<T> extends JPanel implements TypeSafeDataProvider {
  private final MyTree myTree;
  private final JScrollPane myTreeScrollPane;
  @NotNull protected final Project myProject;
  private final boolean myShowCheckboxes;
  private final boolean myHighlightProblems;
  private boolean myShowFlatten;
  private boolean myIsModelFlat;

  @NotNull private final Set<T> myIncludedChanges;
  @NotNull private Runnable myDoubleClickHandler = EmptyRunnable.getInstance();
  private boolean myAlwaysExpandList;

  @NotNull private final MyTreeCellRenderer myNodeRenderer;

  @NonNls private static final String ROOT = "root";

  @NonNls private final static String FLATTEN_OPTION_KEY = "ChangesBrowser.SHOW_FLATTEN";

  @Nullable private final Runnable myInclusionListener;
  @Nullable private ChangeNodeDecorator myChangeDecorator;
  private Runnable myGenericSelectionListener;
  @NotNull private final CopyProvider myTreeCopyProvider;
  private TreeState myNonFlatTreeState;

  public ChangesTreeList(@NotNull Project project,
                         @NotNull Collection<T> initiallyIncluded,
                         final boolean showCheckboxes,
                         final boolean highlightProblems,
                         @Nullable final Runnable inclusionListener,
                         @Nullable final ChangeNodeDecorator decorator) {
    myProject = project;
    myShowCheckboxes = showCheckboxes;
    myHighlightProblems = highlightProblems;
    myInclusionListener = inclusionListener;
    myChangeDecorator = decorator;
    myIncludedChanges = new HashSet<T>(initiallyIncluded);
    myAlwaysExpandList = true;
    final ChangesBrowserNodeRenderer nodeRenderer = new ChangesBrowserNodeRenderer(myProject, () -> myShowFlatten, myHighlightProblems);
    myNodeRenderer = new MyTreeCellRenderer(nodeRenderer);

    setLayout(new BorderLayout());

    final int checkboxWidth = new JCheckBox().getPreferredSize().width;
    myTree = new MyTree(project, checkboxWidth);
    myTree.setHorizontalAutoScrollingEnabled(false);
    myTree.setRootVisible(false);
    myTree.setShowsRootHandles(true);
    myTree.setOpaque(false);
    new TreeSpeedSearch(myTree, new Convertor<TreePath, String>() {
      @Override
      public String convert(TreePath o) {
        ChangesBrowserNode node = (ChangesBrowserNode) o.getLastPathComponent();
        return node.getTextPresentation();
      }
    });
    myTree.setCellRenderer(myNodeRenderer);
    add(myTreeScrollPane = ScrollPaneFactory.createScrollPane(myTree), BorderLayout.CENTER);

    new MyToggleSelectionAction().registerCustomShortcutSet(new CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0)), this);
    if (myShowCheckboxes) {
      registerKeyboardAction(new ActionListener() {
        @Override
        public void actionPerformed(ActionEvent e) {
          includeChanges(getSelectedChangesOrAllIfNone());
        }

      }, KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, 0), JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);

      registerKeyboardAction(new ActionListener() {
        @Override
        public void actionPerformed(ActionEvent e) {
          excludeChanges(getSelectedChangesOrAllIfNone());
        }
      }, KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
    }

    registerKeyboardAction(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent e) {
        myDoubleClickHandler.run();
      }
    }, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);

    myTree.addKeyListener(new KeyAdapter() {
      @Override
      public void keyPressed(KeyEvent e) {
        if (KeyEvent.VK_ENTER == e.getKeyCode() && e.getModifiers() == 0) {
          if (myTree.getSelectionCount() <= 1) {
            Object lastPathComponent = myTree.getLastSelectedPathComponent();
            if (!(lastPathComponent instanceof DefaultMutableTreeNode)) {
              return;
            }
            DefaultMutableTreeNode node = (DefaultMutableTreeNode)lastPathComponent;
            if (!node.isLeaf()) {
              return;
            }
          }
          myDoubleClickHandler.run();
          e.consume();
        }
      }
    });

    new DoubleClickListener() {
      @Override
      protected boolean onDoubleClick(MouseEvent e) {
        final TreePath clickPath = myTree.getUI() instanceof WideSelectionTreeUI
                                   ? myTree.getClosestPathForLocation(e.getX(), e.getY())
                                   : myTree.getPathForLocation(e.getX(), e.getY());
        if (clickPath == null) return false;

        final int row = myTree.getRowForLocation(e.getPoint().x, e.getPoint().y);
        if (row >= 0) {
          if (myShowCheckboxes) {
            final Rectangle baseRect = myTree.getRowBounds(row);
            baseRect.setSize(checkboxWidth, baseRect.height);
            if (baseRect.contains(e.getPoint())) return false;
          }
        }

        myDoubleClickHandler.run();
        return true;
      }
    }.installOn(myTree);

    new TreeLinkMouseListener(myNodeRenderer.myTextRenderer) {
      @Override
      protected int getRendererRelativeX(@NotNull MouseEvent e, @NotNull JTree tree, @NotNull TreePath path) {
        int x = super.getRendererRelativeX(e, tree, path);

        return !myShowCheckboxes ? x : x - myTree.myCheckboxWidth;
      }
    }.installOn(myTree);
    SmartExpander.installOn(myTree);

    setShowFlatten(PropertiesComponent.getInstance(myProject).isTrueValue(FLATTEN_OPTION_KEY));

    String emptyText = StringUtil.capitalize(DiffBundle.message("diff.count.differences.status.text", 0));
    setEmptyText(emptyText);

    myTreeCopyProvider = new ChangesBrowserNodeCopyProvider(myTree);
  }

  public void setEmptyText(@NotNull String emptyText) {
    myTree.getEmptyText().setText(emptyText);
  }

  public void addSelectionListener(final Runnable runnable) {
    myGenericSelectionListener = runnable;
    myTree.addTreeSelectionListener(new TreeSelectionListener() {
      @Override
      public void valueChanged(TreeSelectionEvent e) {
        myGenericSelectionListener.run();
      }
    });
  }

  public void setChangeDecorator(@Nullable ChangeNodeDecorator changeDecorator) {
    myChangeDecorator = changeDecorator;
  }

  public void setDoubleClickHandler(@NotNull final Runnable doubleClickHandler) {
    myDoubleClickHandler = doubleClickHandler;
  }

  public void installPopupHandler(ActionGroup group) {
    PopupHandler.installUnknownPopupHandler(myTree, group, ActionManager.getInstance());
  }
  
  public JComponent getPreferredFocusedComponent() {
    return myTree;
  }

  @Override
  public Dimension getPreferredSize() {
    return new Dimension(400, 400);
  }

  public boolean isShowFlatten() {
    return myShowFlatten;
  }

  public void setScrollPaneBorder(Border border) {
    myTreeScrollPane.setBorder(border);
  }

  public void setShowFlatten(final boolean showFlatten) {
    final List<T> wasSelected = getSelectedChanges();
    if (!myAlwaysExpandList && !myShowFlatten) {
      myNonFlatTreeState = TreeState.createOn(myTree, (DefaultMutableTreeNode)myTree.getModel().getRoot());
    }
    myShowFlatten = showFlatten;
    setChangesToDisplay(getChanges());
    if (!myAlwaysExpandList && !myShowFlatten && myNonFlatTreeState != null) {
      myNonFlatTreeState.applyTo(myTree, (DefaultMutableTreeNode)myTree.getModel().getRoot());
    }
    select(wasSelected);
  }

  private void setChildIndent(boolean isFlat) {
    BasicTreeUI treeUI = (BasicTreeUI)myTree.getUI();

    treeUI.setLeftChildIndent(!isFlat ? UIUtil.getTreeLeftChildIndent() : 0);
    treeUI.setRightChildIndent(!isFlat ? UIUtil.getTreeRightChildIndent() : 0);
  }

  protected boolean isCurrentModelFlat() {
    boolean isFlat = true;
    Enumeration enumeration = getRoot().depthFirstEnumeration();

    while (isFlat && enumeration.hasMoreElements()) {
      isFlat = ((ChangesBrowserNode)enumeration.nextElement()).getLevel() <= 1;
    }

    return isFlat;
  }

  @Override
  public void requestFocus() {
    myTree.requestFocus();
  }

  public void setChangesToDisplay(final List<T> changes) {
    setChangesToDisplay(changes, null);
  }
  
  public void setChangesToDisplay(final List<T> changes, @Nullable final VirtualFile toSelect) {
    final DefaultTreeModel model = buildTreeModel(changes, myChangeDecorator);
    TreeState state = null;
    if (!myAlwaysExpandList) {
      state = TreeState.createOn(myTree, (DefaultMutableTreeNode)myTree.getModel().getRoot());
    }
    myTree.setModel(model);
    myIsModelFlat = isCurrentModelFlat();
    setChildIndent(myShowFlatten && myIsModelFlat);
    if (!myAlwaysExpandList) {
      //noinspection ConstantConditions
      state.applyTo(myTree, (DefaultMutableTreeNode)myTree.getModel().getRoot());
      return;
    }

    final Runnable runnable = new Runnable() {
      @Override
      public void run() {
        if (myProject.isDisposed()) return;
        TreeUtil.expandAll(myTree);

        int selectedTreeRow = -1;

        if (myShowCheckboxes) {
          if (myIncludedChanges.size() > 0) {
            ChangesBrowserNode root = (ChangesBrowserNode)model.getRoot();
            Enumeration enumeration = root.depthFirstEnumeration();

            while (enumeration.hasMoreElements()) {
              ChangesBrowserNode node = (ChangesBrowserNode)enumeration.nextElement();
              @SuppressWarnings("unchecked")
              final CheckboxTree.NodeState state = getNodeStatus(node);
              if (node != root && state == CheckboxTree.NodeState.CLEAR) {
                myTree.collapsePath(new TreePath(node.getPath()));
              }
            }

            enumeration = root.depthFirstEnumeration();
            while (enumeration.hasMoreElements()) {
              ChangesBrowserNode node = (ChangesBrowserNode)enumeration.nextElement();
              @SuppressWarnings("unchecked")
              final CheckboxTree.NodeState state = getNodeStatus(node);
              if (state == CheckboxTree.NodeState.FULL && node.isLeaf()) {
                selectedTreeRow = myTree.getRowForPath(new TreePath(node.getPath()));
                break;
              }
            }
          }
        } else {
          if (toSelect != null) {
            int rowInTree = findRowContainingFile((TreeNode)model.getRoot(), toSelect);
            if (rowInTree > -1) {
              selectedTreeRow = rowInTree;
            }
          }
        }

        if (selectedTreeRow >= 0) {
          myTree.setSelectionRow(selectedTreeRow);
        }
        TreeUtil.showRowCentered(myTree, selectedTreeRow, false);
      }
    };
    if (ApplicationManager.getApplication().isDispatchThread()) {
      runnable.run();
    } else {
      SwingUtilities.invokeLater(runnable);
    }
  }

  private int findRowContainingFile(@NotNull TreeNode root, @NotNull final VirtualFile toSelect) {
    final Ref<Integer> row = Ref.create(-1);
    TreeUtil.traverse(root, new TreeUtil.Traverse() {
      @Override
      public boolean accept(Object node) {
        if (node instanceof DefaultMutableTreeNode) {
          Object userObject = ((DefaultMutableTreeNode)node).getUserObject();
          if (userObject instanceof Change) {
            if (matches((Change)userObject, toSelect)) {
              TreeNode[] path = ((DefaultMutableTreeNode)node).getPath();
              row.set(myTree.getRowForPath(new TreePath(path)));
            }
          }
        }

        return row.get() == -1;
      }
    });
    return row.get();
  }

  private static boolean matches(@NotNull Change change, @NotNull VirtualFile file) {
    VirtualFile virtualFile = change.getVirtualFile();
    return virtualFile != null && virtualFile.equals(file) || seemsToBeMoved(change, file);
  }

  private static boolean seemsToBeMoved(Change change, VirtualFile toSelect) {
    ContentRevision afterRevision = change.getAfterRevision();
    if (afterRevision == null) return false;
    FilePath file = afterRevision.getFile();
    return FileUtil.pathsEqual(file.getPath(), toSelect.getPath());
  }

  protected abstract DefaultTreeModel buildTreeModel(final List<T> changes, final ChangeNodeDecorator changeNodeDecorator);

  private void toggleSelection() {
    toggleChanges(getSelectedChanges());
  }

  /**
   * TODO: This method does not respect T type parameter while filling the result - just "Change" class is used
   * TODO: ("ChangesBrowserNode.getAllChangesUnder()").
   */
  @NotNull
  public List<T> getChanges() {
    //noinspection unchecked
    return ((ChangesBrowserNode)getRoot()).getAllChangesUnder();
  }

  @NotNull
  public List<T> getSelectedChanges() {
    final TreePath[] paths = myTree.getSelectionPaths();
    if (paths == null) {
      return Collections.emptyList();
    }
    else {
      LinkedHashSet<T> changes = ContainerUtil.newLinkedHashSet();
      for (TreePath path : paths) {
        //noinspection unchecked
        changes.addAll(getSelectedObjects((ChangesBrowserNode)path.getLastPathComponent()));
      }
      return ContainerUtil.newArrayList(changes);
    }
  }

  @NotNull
  private List<T> getSelectedChangesOrAllIfNone() {
    List<T> changes = getSelectedChanges();
    if (!changes.isEmpty()) return changes;
    return getChanges();
  }

  protected abstract List<T> getSelectedObjects(final ChangesBrowserNode<T> node);

  @Nullable
  protected abstract T getLeadSelectedObject(final ChangesBrowserNode node);

  @Nullable
  public T getHighestLeadSelection() {
    final TreePath path = myTree.getSelectionPath();
    if (path == null) {
      return null;
    }
    //noinspection unchecked
    return getLeadSelectedObject((ChangesBrowserNode<T>)path.getLastPathComponent());
  }

  @Nullable
  public T getLeadSelection() {
    final TreePath path = myTree.getSelectionPath();
    //noinspection unchecked
    return path == null ? null : ContainerUtil.getFirstItem(getSelectedObjects(((ChangesBrowserNode<T>)path.getLastPathComponent())));
  }

  @NotNull
  ChangesBrowserNode<?> getRoot() {
    return (ChangesBrowserNode<?>)myTree.getModel().getRoot();
  }

  @Nullable
  TreePath[] getSelectionPaths() {
    return myTree.getSelectionPaths();
  }

  private void notifyInclusionListener() {
    if (myInclusionListener != null) {
      myInclusionListener.run();
    }
  }

  // no listener supposed to be called
  public void setIncludedChanges(final Collection<T> changes) {
    myIncludedChanges.clear();
    myIncludedChanges.addAll(changes);
    myTree.repaint();
  }

  public void includeChange(final T change) {
    includeChanges(Collections.singleton(change));
  }

  public void includeChanges(final Collection<T> changes) {
    myIncludedChanges.addAll(changes);
    notifyInclusionListener();
    myTree.repaint();
  }

  public void excludeChange(final T change) {
    excludeChanges(Collections.singleton(change));
  }

  public void excludeChanges(final Collection<T> changes) {
    myIncludedChanges.removeAll(changes);
    notifyInclusionListener();
    myTree.repaint();
  }

  protected void toggleChanges(final Collection<T> changes) {
    boolean hasExcluded = false;
    for (T value : changes) {
      if (!myIncludedChanges.contains(value)) {
        hasExcluded = true;
        break;
      }
    }

    if (hasExcluded) {
      includeChanges(changes);
    }
    else {
      excludeChanges(changes);
    }
  }

  public boolean isIncluded(final T change) {
    return myIncludedChanges.contains(change);
  }

  @NotNull
  public Collection<T> getIncludedChanges() {
    return myIncludedChanges;
  }

  public void expandAll() {
    TreeUtil.expandAll(myTree);
  }

  public AnAction[] getTreeActions() {
    final ToggleShowDirectoriesAction directoriesAction = new ToggleShowDirectoriesAction();
    final ExpandAllAction expandAllAction = new ExpandAllAction(myTree) {
      @Override
      public void update(AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(!myShowFlatten || !myIsModelFlat);
      }
    };
    final CollapseAllAction collapseAllAction = new CollapseAllAction(myTree) {
      @Override
      public void update(AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(!myShowFlatten || !myIsModelFlat);
      }
    };
    final AnAction[] actions = new AnAction[]{directoriesAction, expandAllAction, collapseAllAction};
    directoriesAction.registerCustomShortcutSet(
      new CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_P, SystemInfo.isMac ? InputEvent.META_DOWN_MASK : InputEvent.CTRL_DOWN_MASK)),
      this);
    expandAllAction.registerCustomShortcutSet(
      new CustomShortcutSet(KeymapManager.getInstance().getActiveKeymap().getShortcuts(IdeActions.ACTION_EXPAND_ALL)),
      myTree);
    collapseAllAction.registerCustomShortcutSet(
      new CustomShortcutSet(KeymapManager.getInstance().getActiveKeymap().getShortcuts(IdeActions.ACTION_COLLAPSE_ALL)),
      myTree);
    return actions;
  }

  public void setSelectionMode(@JdkConstants.TreeSelectionMode int mode) {
    myTree.getSelectionModel().setSelectionMode(mode);
  }

  private class MyTreeCellRenderer extends JPanel implements TreeCellRenderer {
    private final ChangesBrowserNodeRenderer myTextRenderer;
    private final JCheckBox myCheckBox;


    public MyTreeCellRenderer(@NotNull ChangesBrowserNodeRenderer textRenderer) {
      super(new BorderLayout());
      myCheckBox = new JCheckBox();
      myTextRenderer = textRenderer;

      if (myShowCheckboxes) {
        add(myCheckBox, BorderLayout.WEST);
      }

      add(myTextRenderer, BorderLayout.CENTER);
      setOpaque(false);
    }

    @Override
    public Component getTreeCellRendererComponent(JTree tree,
                                                  Object value,
                                                  boolean selected,
                                                  boolean expanded,
                                                  boolean leaf,
                                                  int row,
                                                  boolean hasFocus) {

      if (UIUtil.isUnderGTKLookAndFeel() || UIUtil.isUnderNimbusLookAndFeel()) {
        NonOpaquePanel.setTransparent(this);
        NonOpaquePanel.setTransparent(myCheckBox);
      } else {
        setBackground(null);
        myCheckBox.setBackground(null);
        myCheckBox.setOpaque(false);
      }

      myTextRenderer.setOpaque(false);
      myTextRenderer.setTransparentIconBackground(true);
      myTextRenderer.setToolTipText(null);
      myTextRenderer.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
      if (myShowCheckboxes) {
        @SuppressWarnings("unchecked")
        CheckboxTree.NodeState state = getNodeStatus((ChangesBrowserNode)value);
        myCheckBox.setSelected(state != CheckboxTree.NodeState.CLEAR);
        //noinspection unchecked
        myCheckBox.setEnabled(tree.isEnabled() && isNodeEnabled((ChangesBrowserNode)value));
        revalidate();

        return this;
      }
      else {
        return myTextRenderer;
      }
    }

    @Override
    public String getToolTipText() {
      return myTextRenderer.getToolTipText();
    }
  }


  private CheckboxTree.NodeState getNodeStatus(ChangesBrowserNode<T> node) {
    boolean hasIncluded = false;
    boolean hasExcluded = false;

    for (T change : getSelectedObjects(node)) {
      if (myIncludedChanges.contains(change)) {
        hasIncluded = true;
      }
      else {
        hasExcluded = true;
      }
    }

    if (hasIncluded && hasExcluded) return CheckboxTree.NodeState.PARTIAL;
    if (hasIncluded) return CheckboxTree.NodeState.FULL;
    return CheckboxTree.NodeState.CLEAR;
  }

   protected boolean isNodeEnabled(ChangesBrowserNode<T> node) {
    return getNodeStatus(node) != CheckboxTree.NodeState.PARTIAL;
  }

  private class MyToggleSelectionAction extends AnAction implements DumbAware {
    @Override
    public void actionPerformed(AnActionEvent e) {
      toggleChanges(getSelectedChangesOrAllIfNone());
    }
  }

  public class ToggleShowDirectoriesAction extends ToggleAction implements DumbAware {
    public ToggleShowDirectoriesAction() {
      super(VcsBundle.message("changes.action.show.directories.text"),
            VcsBundle.message("changes.action.show.directories.description"),
            AllIcons.Actions.GroupByPackage);
    }

    @Override
    public boolean isSelected(AnActionEvent e) {
      return (! myProject.isDisposed()) && !PropertiesComponent.getInstance(myProject).isTrueValue(FLATTEN_OPTION_KEY);
    }

    @Override
    public void setSelected(AnActionEvent e, boolean state) {
      PropertiesComponent.getInstance(myProject).setValue(FLATTEN_OPTION_KEY, String.valueOf(!state));
      setShowFlatten(!state);
    }
  }

  public void select(final List<T> changes) {
    final DefaultTreeModel treeModel = (DefaultTreeModel) myTree.getModel();
    final TreeNode root = (TreeNode) treeModel.getRoot();
    final List<TreePath> treeSelection = new ArrayList<TreePath>(changes.size());
    TreeUtil.traverse(root, new TreeUtil.Traverse() {
      @Override
      public boolean accept(Object node) {
        @SuppressWarnings("unchecked")
        final T change = (T) ((DefaultMutableTreeNode) node).getUserObject();
        if (changes.contains(change)) {
          treeSelection.add(new TreePath(((DefaultMutableTreeNode) node).getPath()));
        }
        return true;
      }
    });
    myTree.setSelectionPaths(treeSelection.toArray(new TreePath[treeSelection.size()]));
    if (treeSelection.size() == 1) myTree.scrollPathToVisible(treeSelection.get(0));
  }

  public void setAlwaysExpandList(boolean alwaysExpandList) {
    myAlwaysExpandList = alwaysExpandList;
  }

  public void setPaintBusy(final boolean value) {
    myTree.setPaintBusy(value);
  }

  @Override
  public void calcData(DataKey key, DataSink sink) {
    if (PlatformDataKeys.COPY_PROVIDER == key) {
      sink.put(PlatformDataKeys.COPY_PROVIDER, myTreeCopyProvider);
    }
  }

  private class MyTree extends Tree implements TypeSafeDataProvider {

    private final Project myProject;
    private final int myCheckboxWidth;

    public MyTree(Project project, int checkboxWidth) {
      super(ChangesBrowserNode.create(ChangesTreeList.this.myProject, ROOT));
      myProject = project;
      myCheckboxWidth = checkboxWidth;
    }

    @Override
    public boolean isFileColorsEnabled() {
      final boolean enabled = Registry.is("file.colors.in.commit.dialog")
                        && FileColorManager.getInstance(myProject).isEnabled()
                        && FileColorManager.getInstance(myProject).isEnabledForProjectView();
      final boolean opaque = isOpaque();
      if (enabled && opaque) {
        setOpaque(false);
      } else if (!enabled && !opaque) {
        setOpaque(true);
      }
      return enabled;
    }

    @Override
    public Color getFileColorFor(Object object) {
      VirtualFile file = null;
      if (object instanceof FilePath) {
        file = LocalFileSystem.getInstance().findFileByPath(((FilePath)object).getPath());
      } else if (object instanceof Change) {
        file = ((Change)object).getVirtualFile();
      }

      if (file != null) {
        return FileColorManager.getInstance(myProject).getFileColor(file);
      }
      return super.getFileColorFor(object);
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
      Dimension size = super.getPreferredScrollableViewportSize();
      size = new Dimension(size.width + 10, size.height);
      return size;
    }

    @Override
    protected void processMouseEvent(MouseEvent e) {
      if (e.getID() == MouseEvent.MOUSE_PRESSED) {
        if (! myTree.isEnabled()) return;
        int row = myTree.getRowForLocation(e.getX(), e.getY());
        if (row >= 0) {
          final Rectangle baseRect = myTree.getRowBounds(row);
          baseRect.setSize(myCheckboxWidth, baseRect.height);
          if (baseRect.contains(e.getPoint())) {
            myTree.setSelectionRow(row);
            toggleSelection();
          }
        }
      }
      super.processMouseEvent(e);
    }

    @Override
    public int getToggleClickCount() {
      return -1;
    }

    @Override
    public void calcData(DataKey key, DataSink sink) {
      // just delegate to the change list
      ChangesTreeList.this.calcData(key, sink);
    }
  }
}
