package forpdateam.ru.forpda.fragments.forum;

import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.Nullable;
import android.support.v4.widget.NestedScrollView;
import android.support.v7.app.AlertDialog;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.unnamed.b.atv.model.TreeNode;
import com.unnamed.b.atv.view.AndroidTreeView;

import java.util.ArrayList;
import java.util.List;

import forpdateam.ru.forpda.R;
import forpdateam.ru.forpda.TabManager;
import forpdateam.ru.forpda.api.Api;
import forpdateam.ru.forpda.api.forum.models.ForumItemFlat;
import forpdateam.ru.forpda.api.forum.models.ForumItemTree;
import forpdateam.ru.forpda.fragments.TabFragment;
import forpdateam.ru.forpda.fragments.topics.TopicsFragment;
import forpdateam.ru.forpda.utils.AlertDialogMenu;
import forpdateam.ru.forpda.utils.Utils;
import io.realm.Realm;
import io.realm.RealmResults;

/**
 * Created by radiationx on 15.02.17.
 */

public class ForumFragment extends TabFragment {
    public final static String ARG_FORUM_ID = "ARG_FORUM_ID";
    private Subscriber<ForumItemTree> mainSubscriber = new Subscriber<>();
    private NestedScrollView treeContainer;
    private Realm realm;
    private RealmResults<ForumItemFlat> results;
    private static AlertDialogMenu<ForumFragment, ForumItemTree> forumMenu, showedForumMenu;
    private AlertDialog updateDialog;
    boolean firstLoad = true;
    private TreeNode.TreeNodeClickListener nodeClickListener = (node, value) -> {
        ForumItemTree item = (ForumItemTree) value;
        if (item.getForums() == null) {
            Bundle args = new Bundle();
            args.putInt(TopicsFragment.TOPICS_ID_ARG, item.getId());
            TabManager.getInstance().add(new TabFragment.Builder<>(TopicsFragment.class).setArgs(args).build());
        }
    };
    private TreeNode.TreeNodeLongClickListener nodeLongClickListener = (node, value) -> {
        ForumItemTree item = (ForumItemTree) value;
        if (forumMenu == null) {
            forumMenu = new AlertDialogMenu<>();
            showedForumMenu = new AlertDialogMenu<>();
            forumMenu.addItem("Открыть форум", (context, data) -> {
                Bundle args = new Bundle();
                args.putInt(TopicsFragment.TOPICS_ID_ARG, data.getId());
                TabManager.getInstance().add(new TabFragment.Builder<>(TopicsFragment.class).setArgs(args).build());
            });
            forumMenu.addItem("Скопировать ссылку", (context, data) -> Utils.copyToClipBoard("http://4pda.ru/forum/index.php?showforum=".concat(Integer.toString(data.getId()))));
            forumMenu.addItem("Отметить прочитанным", (context, data) -> {

            });
        }
        showedForumMenu.clear();
        if (item.getLevel() > 0)
            showedForumMenu.addItem(forumMenu.get(0));
        showedForumMenu.addItem(forumMenu.get(1));
        showedForumMenu.addItem(forumMenu.get(2));
        new AlertDialog.Builder(getContext())
                .setItems(showedForumMenu.getTitles(), (dialogInterface, i) -> showedForumMenu.onClick(i, ForumFragment.this, item))
                .show();
        return false;
    };
    TreeNode root;
    AndroidTreeView tView;
    int forumId = -1;


    @Override
    public String getDefaultTitle() {
        return "Форум";
    }


    @Override
    public boolean isUseCache() {
        return true;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        realm = Realm.getDefaultInstance();
        if (getArguments() != null) {
            forumId = getArguments().getInt(ARG_FORUM_ID, -1);
        }
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        initBaseView(inflater, container);
        setWhiteBackground();
        baseInflateFragment(inflater, R.layout.fragment_forum);
        treeContainer = (NestedScrollView) findViewById(R.id.nested_scroll_view);

        viewsReady();
        toolbar.getMenu().add("Обновить форумы").setOnMenuItemClickListener(item -> {
            loadData();
            return false;
        });

        bindView();
        return view;
    }


    @Override
    public void loadData() {
        firstLoad = false;
        updateDialog = new AlertDialog.Builder(getContext())
                .setTitle("Обновление")
                .setMessage("Загрузка данных")
                .setCancelable(false)
                .show();
        mainSubscriber.subscribe(Api.Forum().getForums(), this::onLoadThemes, new ForumItemTree(), null);
    }

    private void onLoadThemes(ForumItemTree forumRoot) {
        updateDialog.setMessage("Обновление базы данных");

        if (forumRoot.getForums() == null) {
            updateDialog.setMessage("Произошла ошибка");
            new Handler().postDelayed(() -> {
                if (updateDialog != null)
                    updateDialog.cancel();
            }, 500);
            return;
        }


        realm.executeTransactionAsync(r -> {
            r.delete(ForumItemFlat.class);
            List<ForumItemFlat> items = new ArrayList<>();
            Api.Forum().transformToList(items, forumRoot);
            r.copyToRealmOrUpdate(items);
        }, this::bindView);
        //setSubtitle(data.getAll() <= 1 ? null : "" + data.getCurrent() + "/" + data.getAll());


    }

    private void bindView() {
        results = realm.where(ForumItemFlat.class).findAll();
        if (updateDialog != null && updateDialog.isShowing()) {
            if (results.size() != 0) {
                updateDialog.setMessage("Обновление прошло успешно");
            } else {
                updateDialog.setMessage("Произошла ошибка");
            }
            new Handler().postDelayed(() -> {
                if (updateDialog != null)
                    updateDialog.cancel();
            }, 500);
        }
        if (results.size() == 0) {
            if (firstLoad)
                loadData();
        } else {
            //adapter.addAll(results);
            ForumItemTree rootForum = new ForumItemTree();

            Api.Forum().transformToTree(results, rootForum);

            tView = new AndroidTreeView(getContext());
            root = TreeNode.root();
            recourse(rootForum, root);
            tView.setRoot(root);

            tView.setDefaultContainerStyle(R.style.TreeNodeStyleCustom);
            tView.setDefaultViewHolder(DefaultForumHolder.class);
            tView.setDefaultNodeClickListener(nodeClickListener);
            tView.setDefaultNodeLongClickListener(nodeLongClickListener);
            treeContainer.removeAllViews();
            View suka = tView.getView();
            treeContainer.addView(suka);

            //int id = 427;
            //int id = 828;
            //int id = 282;
            //int id = 269;
            if (forumId != -1) {
                scrollToForum(forumId);
                forumId = -1;
            }
        }
    }


    private void scrollToForum(int id) {
        final TreeNode targetNode = findNodeById(id, root);

        if (targetNode != null) {
            TreeNode upToParent = targetNode;
            while (upToParent.getParent() != null) {
                tView.expandNode(upToParent);
                upToParent = upToParent.getParent();
            }
        }
    }

    private TreeNode findNodeById(int id, TreeNode root) {
        if (root.getValue() != null && ((ForumItemTree) root.getValue()).getId() == id) return root;
        if (root.getChildren() == null && root.getChildren().size() == 0) return null;
        for (TreeNode item : root.getChildren()) {
            TreeNode node = findNodeById(id, item);
            if (node != null) return node;
        }
        return null;
    }

    private void recourse(ForumItemTree rootForum, TreeNode rootNode) {
        if (rootForum.getForums() == null) return;
        for (ForumItemTree item : rootForum.getForums()) {
            TreeNode child = new TreeNode(item);
            recourse(item, child);
            rootNode.addChild(child);
        }
    }


    public static boolean checkIsLink(int id) {
        return Realm.getDefaultInstance().where(ForumItemFlat.class).equalTo("parentId", id).findAll().size() == 0;
    }
}