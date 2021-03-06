/*
 * Copyright 2000-2016 JetBrains s.r.o.
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

/*
 * Created by IntelliJ IDEA.
 * User: max
 * Date: Nov 4, 2001
 * Time: 5:19:35 PM
 */
package com.intellij.codeInspection.ui;

import com.intellij.codeInspection.CommonProblemDescriptor;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ex.GlobalInspectionContextImpl;
import com.intellij.codeInspection.ex.InspectionToolWrapper;
import com.intellij.codeInspection.reference.RefEntity;
import com.intellij.openapi.project.Project;
import com.intellij.profile.codeInspection.ui.inspectionsTree.InspectionsConfigTreeComparator;
import com.intellij.psi.util.PsiUtilCore;
import com.intellij.ui.TreeSpeedSearch;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.SmartList;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.Convertor;
import com.intellij.util.containers.MultiMap;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.ui.tree.TreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.ExpandVetoException;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import java.util.*;

public class InspectionTree extends Tree {
  private static final Comparator<CommonProblemDescriptor> DESCRIPTOR_COMPARATOR = (c1, c2) -> {
    if (c1 instanceof ProblemDescriptor && c2 instanceof ProblemDescriptor) {
      return PsiUtilCore.compareElementsByPosition(((ProblemDescriptor)c2).getPsiElement(),
                                                   ((ProblemDescriptor)c1).getPsiElement());
    }
    return c1.getDescriptionTemplate().compareTo(c2.getDescriptionTemplate());
  };

  @NotNull private final GlobalInspectionContextImpl myContext;
  @NotNull private final ExcludedInspectionTreeNodesManager myExcludedManager;
  @NotNull private InspectionTreeState myState = new InspectionTreeState();
  private boolean myQueueUpdate;

  public InspectionTree(@NotNull Project project,
                        @NotNull GlobalInspectionContextImpl context,
                        @NotNull InspectionResultsView view) {
    setModel(new DefaultTreeModel(new InspectionRootNode(project, new InspectionTreeUpdater(view))));
    myContext = context;
    myExcludedManager = view.getExcludedManager();

    setCellRenderer(new InspectionTreeCellRenderer(view));
    setRootVisible(false);
    setShowsRootHandles(true);
    UIUtil.setLineStyleAngled(this);
    addTreeWillExpandListener(new ExpandListener());

    myState.getExpandedUserObjects().add(project);

    TreeUtil.installActions(this);
    new TreeSpeedSearch(this, new Convertor<TreePath, String>() {
      @Override
      public String convert(TreePath o) {
        return InspectionsConfigTreeComparator.getDisplayTextToSort(o.getLastPathComponent().toString());
      }
    });

    addTreeSelectionListener(new TreeSelectionListener() {
      @Override
      public void valueChanged(TreeSelectionEvent e) {
        TreePath newSelection = e.getNewLeadSelectionPath();
        if (newSelection != null && !isUnderQueueUpdate()) {
          myState.setSelectionPath(newSelection);
        }
      }
    });
  }

  public void setQueueUpdate(boolean queueUpdate) {
    myQueueUpdate = queueUpdate;
  }

  public boolean isUnderQueueUpdate() {
    return myQueueUpdate;
  }

  public void removeAllNodes() {
    getRoot().removeAllChildren();
    nodeStructureChanged(getRoot());
  }

  public InspectionTreeNode getRoot() {
    return (InspectionTreeNode)getModel().getRoot();
  }

  @Nullable
  public InspectionToolWrapper getSelectedToolWrapper() {
    final TreePath[] paths = getSelectionPaths();
    if (paths == null) return null;
    InspectionToolWrapper toolWrapper = null;
    for (TreePath path : paths) {
      Object[] nodes = path.getPath();
      for (int j = nodes.length - 1; j >= 0; j--) {
        Object node = nodes[j];
        if (node instanceof InspectionGroupNode) {
          return null;
        }
        if (node instanceof InspectionNode) {
          InspectionToolWrapper wrapper = ((InspectionNode)node).getToolWrapper();
          if (toolWrapper == null) {
            toolWrapper = wrapper;
          }
          else if (toolWrapper != wrapper) {
            return null;
          }
          break;
        }
      }
    }

    return toolWrapper;
  }

  @Nullable
  public RefEntity getCommonSelectedElement() {
    final Object node = getCommonSelectedNode();
    return node instanceof RefElementNode ? ((RefElementNode)node).getElement() : null;
  }

  @Nullable
  private Object getCommonSelectedNode() {
    final TreePath[] paths = getSelectionPaths();
    if (paths == null) return null;
    final Object[][] resolvedPaths = new Object[paths.length][];
    for (int i = 0; i < paths.length; i++) {
      TreePath path = paths[i];
      resolvedPaths[i] = path.getPath();
    }

    Object currentCommonNode = null;
    for (int i = 0; i < resolvedPaths[0].length; i++) {
      final Object currentNode = resolvedPaths[0][i];
      for (int j = 1; j < resolvedPaths.length; j++) {
        final Object o = resolvedPaths[j][i];
        if (!o.equals(currentNode)) {
          return currentCommonNode;
        }
      }
      currentCommonNode = currentNode;
    }
    return currentCommonNode;
  }

  @NotNull
  public RefEntity[] getSelectedElements() {
    TreePath[] selectionPaths = getSelectionPaths();
    if (selectionPaths != null) {
      InspectionToolWrapper toolWrapper = getSelectedToolWrapper();
      if (toolWrapper == null) return RefEntity.EMPTY_ELEMENTS_ARRAY;

      List<RefEntity> result = new ArrayList<RefEntity>();
      for (TreePath selectionPath : selectionPaths) {
        final InspectionTreeNode node = (InspectionTreeNode)selectionPath.getLastPathComponent();
        addElementsInNode(node, result);
      }
      return result.toArray(new RefEntity[result.size()]);
    }
    return RefEntity.EMPTY_ELEMENTS_ARRAY;
  }

  private static void addElementsInNode(InspectionTreeNode node, List<RefEntity> out) {
    if (!node.isValid()) return;
    if (node instanceof RefElementNode) {
      final RefEntity element = ((RefElementNode)node).getElement();
      if (!out.contains(element)) {
        out.add(0, element);
      }
    }
    if (node instanceof ProblemDescriptionNode) {
      final RefEntity element = ((ProblemDescriptionNode)node).getElement();
      if (!out.contains(element)) {
        out.add(0, element);
      }
    }
    final Enumeration children = node.children();
    while (children.hasMoreElements()) {
      InspectionTreeNode child = (InspectionTreeNode)children.nextElement();
      addElementsInNode(child, out);
    }
  }

  public CommonProblemDescriptor[] getSelectedDescriptors() {
    return getSelectedDescriptors(false);
  }

  public CommonProblemDescriptor[] getSelectedDescriptors(boolean sortedByPosition) {
    final TreePath[] paths = getSelectionPaths();
    if (paths == null) return CommonProblemDescriptor.EMPTY_ARRAY;
    final TreePath[] selectionPaths = TreeUtil.selectMaximals(paths);
    final LinkedHashSet<CommonProblemDescriptor> descriptors = new LinkedHashSet<CommonProblemDescriptor>();

    MultiMap<Object, CommonProblemDescriptor> parentToChildNode = new MultiMap<>();
    final List<InspectionTreeNode> nonDescriptorNodes = new SmartList<>();
    for (TreePath path : selectionPaths) {
      final Object[] pathAsArray = path.getPath();
      final int length = pathAsArray.length;
      final Object node = pathAsArray[length - 1];
      if (node instanceof ProblemDescriptionNode) {
        if (isNodeValidAndIncluded((ProblemDescriptionNode)node)) {
          final CommonProblemDescriptor descriptor = ((ProblemDescriptionNode)node).getDescriptor();
          if (length >= 2) {
            parentToChildNode.putValue(pathAsArray[length - 2], descriptor);
          } else {
            parentToChildNode.putValue(node, descriptor);
          }
        }
      } else {
        nonDescriptorNodes.add((InspectionTreeNode)node);
      }
    }

    for (InspectionTreeNode node : nonDescriptorNodes) {
      processChildDescriptorsDeep(node, descriptors, sortedByPosition);
    }

    for (Map.Entry<Object, Collection<CommonProblemDescriptor>> entry : parentToChildNode.entrySet()) {
      final Collection<CommonProblemDescriptor> siblings = entry.getValue();
      if (siblings.size() == 1) {
        @SuppressWarnings("ConstantConditions")
        final CommonProblemDescriptor descriptor = ContainerUtil.getFirstItem(siblings);
        if (descriptor != null) {
          descriptors.add(descriptor);
        }
      } else {
        if (sortedByPosition) {
          siblings.stream().sorted(DESCRIPTOR_COMPARATOR).forEach(descriptors::add);
        } else {
          descriptors.addAll(siblings);
        }
      }
    }

    return descriptors.toArray(new CommonProblemDescriptor[descriptors.size()]);
  }

  public boolean areDescriptorNodesSelected() {
    final TreePath[] paths = getSelectionPaths();
    if (paths == null) return false;
    for (TreePath path : paths) {
      if (!(path.getLastPathComponent() instanceof ProblemDescriptionNode)) {
        return false;
      }
    }
    return true;
  }

  public int getSelectedProblemCount() {
    if (getSelectionCount() == 0) return 0;
    final TreePath[] paths = getSelectionPaths();

    Set<InspectionTreeNode> result = new HashSet<>();
    MultiMap<InspectionTreeNode, InspectionTreeNode> rootDependencies = new MultiMap<>();
    for (TreePath path : paths) {

      final InspectionTreeNode node = (InspectionTreeNode)path.getLastPathComponent();
      final Collection<InspectionTreeNode> visitedChildren = rootDependencies.get(node);
      for (InspectionTreeNode child : visitedChildren) {
        result.remove(child);
      }

      boolean needToAdd = true;
      for (int i = 0; i < path.getPathCount() - 1; i++) {
        final InspectionTreeNode parent = (InspectionTreeNode) path.getPathComponent(i);
        rootDependencies.putValue(parent, node);
        if (result.contains(parent)) {
          needToAdd = false;
          break;
        }
      }

      if (needToAdd) {
        result.add(node);
      }
    }

    int count = 0;
    for (InspectionTreeNode node : result) {
      count += node.getProblemCount();
    }
    return count;
  }

  private void processChildDescriptorsDeep(InspectionTreeNode node,
                                           LinkedHashSet<CommonProblemDescriptor> descriptors,
                                           boolean sortedByPosition) {
    List<CommonProblemDescriptor> descriptorChildren = null;
    for (int i = 0; i < node.getChildCount(); i++) {
      final TreeNode child = node.getChildAt(i);
      if (child instanceof ProblemDescriptionNode) {
        if (isNodeValidAndIncluded((ProblemDescriptionNode)child)) {
          if (sortedByPosition) {
            if (descriptorChildren == null) {
              descriptorChildren = new ArrayList<>();
            }
            descriptorChildren.add(((ProblemDescriptionNode)child).getDescriptor());
          } else {
            descriptors.add(((ProblemDescriptionNode)child).getDescriptor());
          }
        }
      }
      else {
        processChildDescriptorsDeep((InspectionTreeNode)child, descriptors, sortedByPosition);
      }
    }

    if (descriptorChildren != null) {
      Collections.sort(descriptorChildren, DESCRIPTOR_COMPARATOR);
      descriptors.addAll(descriptorChildren);
    }
  }

  private boolean isNodeValidAndIncluded(ProblemDescriptionNode node) {
    return node.isValid() &&
           !node.isExcluded(myExcludedManager) &&
           !node.isAlreadySuppressedFromView() &&
           !node.isQuickFixAppliedFromView();
  }

  private void nodeStructureChanged(InspectionTreeNode node) {
    ((DefaultTreeModel)getModel()).nodeStructureChanged(node);
  }

  public void queueUpdate() {
    ((InspectionRootNode) getRoot()).getUpdater().update(null, true);
  }

  public void restoreExpansionAndSelection(@Nullable InspectionTreeNode reloadedNode) {
    myState.restoreExpansionAndSelection(this, reloadedNode);
  }

  public void setState(@NotNull InspectionTreeState state) {
    myState = state;
  }

  public InspectionTreeState getTreeState() {
    return myState;
  }

  public void setTreeState(@NotNull InspectionTreeState treeState) {
    myState = treeState;
  }

  private class ExpandListener implements TreeWillExpandListener {
    @Override
    public void treeWillExpand(TreeExpansionEvent event) throws ExpandVetoException {
      final InspectionTreeNode node = (InspectionTreeNode)event.getPath().getLastPathComponent();
      myState.getExpandedUserObjects().add(node.getUserObject());
    }

    @Override
    public void treeWillCollapse(TreeExpansionEvent event) throws ExpandVetoException {
      InspectionTreeNode node = (InspectionTreeNode)event.getPath().getLastPathComponent();
      myState.getExpandedUserObjects().remove(node.getUserObject());
    }
  }

  @NotNull
  public GlobalInspectionContextImpl getContext() {
    return myContext;
  }
}
