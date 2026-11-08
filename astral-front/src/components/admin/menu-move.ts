import type { SysMenu } from '@/api/menu';

export interface MenuOrderWrite {
  id: number;
  parentId: number;
  sort: number;
}
export interface MenuMovePlan {
  tree: SysMenu[];
  updates: MenuOrderWrite[];
}
interface MenuContext {
  node: SysMenu;
  siblings: SysMenu[];
  index: number;
  parentId: number;
}

function findContext(nodes: SysMenu[], id: number, parentId = 0): MenuContext | undefined {
  for (let index = 0; index < nodes.length; index += 1) {
    const node = nodes[index];
    if (node.id === id) return { node, siblings: nodes, index, parentId };
    const found = findContext(node.children || [], id, node.id);
    if (found) return found;
  }
  return undefined;
}

export function menuDescendantIds(nodes: SysMenu[], id: number): Set<number> {
  const result = new Set<number>();
  const source = findContext(nodes, id);
  const visit = (node: SysMenu) => {
    if (result.has(node.id)) return;
    result.add(node.id);
    node.children?.forEach(visit);
  };
  if (source) visit(source.node);
  return result;
}

export interface MenuSiblingTargets {
  previousId?: number;
  nextId?: number;
}

export function indexMenuSiblingTargets(nodes: SysMenu[]): Map<number, MenuSiblingTargets> {
  const result = new Map<number, MenuSiblingTargets>();
  const walk = (siblings: SysMenu[]) => {
    const real = siblings.filter((node) => node.id > 0);
    real.forEach((node, index) =>
      result.set(node.id, { previousId: real[index - 1]?.id, nextId: real[index + 1]?.id }),
    );
    siblings.forEach((node) => {
      if (node.children) walk(node.children);
    });
  };
  walk(nodes);
  return result;
}

export function menuSiblingTargets(nodes: SysMenu[], id: number): MenuSiblingTargets {
  return indexMenuSiblingTargets(nodes).get(id) || { previousId: undefined, nextId: undefined };
}

/** Immutable planner; DOM drop and keyboard moves use the same production contract. */
export function planMenuMove(
  original: SysMenu[],
  sourceId: number,
  targetId: number,
  position: 'before' | 'after' = 'before',
): MenuMovePlan {
  if (sourceId <= 0 || targetId <= 0) throw new Error('插件声明菜单为只读，不能移动');
  const originals = new Map<number, SysMenu>();
  const clone = (nodes: SysMenu[]): SysMenu[] =>
    nodes.map((node) => {
      if (originals.has(node.id)) throw new Error('菜单树包含重复ID，请重新加载');
      originals.set(node.id, node);
      return { ...node, ...(node.children ? { children: clone(node.children) } : {}) };
    });
  const tree = clone(original);
  if (sourceId === targetId) return { tree, updates: [] };
  if (menuDescendantIds(tree, sourceId).has(targetId)) throw new Error('不能移动到自身的子菜单中');
  const source = findContext(tree, sourceId);
  if (!source || !findContext(tree, targetId)) throw new Error('菜单已经变化，请重新加载');
  const [moved] = source.siblings.splice(source.index, 1);
  // Destination index MUST be resolved after removing the source from a shared siblings array.
  const target = findContext(tree, targetId);
  if (!target) throw new Error('目标菜单已经变化，请重新加载');
  moved.parentId = target.parentId;
  target.siblings.splice(target.index + (position === 'after' ? 1 : 0), 0, moved);
  const touched = new Map<SysMenu[], number>([
    [source.siblings, source.parentId],
    [target.siblings, target.parentId],
  ]);
  const updates = new Map<number, MenuOrderWrite>();
  for (const [siblings, parentId] of touched) {
    let sort = 0;
    for (const node of siblings) {
      if (node.id <= 0) continue;
      const previous = originals.get(node.id);
      node.sort = sort++;
      node.parentId = parentId;
      if (previous?.sort !== node.sort || previous?.parentId !== parentId) {
        updates.set(node.id, { id: node.id, sort: node.sort, parentId });
      }
    }
  }
  return { tree, updates: [...updates.values()] };
}
