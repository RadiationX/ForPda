package forpdateam.ru.forpda.presentation.articles.detail.content

import moxy.viewstate.strategy.AddToEndSingleStrategy
import moxy.viewstate.strategy.StateStrategyType
import forpdateam.ru.forpda.common.mvp.IBaseView
import forpdateam.ru.forpda.entity.remote.news.DetailsPage

/**
 * Created by radiationx on 01.01.18.
 */

@StateStrategyType(AddToEndSingleStrategy::class)
interface ArticleContentView : IBaseView {
    fun showData(article: DetailsPage)
    fun setStyleType(type: String)
    fun setFontSize(size: Int)
}
