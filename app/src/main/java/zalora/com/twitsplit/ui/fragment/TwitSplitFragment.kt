package zalora.com.twitsplit.ui.fragment

import android.arch.lifecycle.Observer
import android.arch.lifecycle.ViewModelProvider
import android.arch.lifecycle.ViewModelProviders
import android.databinding.DataBindingUtil
import android.os.Bundle
import android.support.v4.app.Fragment
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.text.Editable
import android.view.*
import android.widget.Toast
import io.reactivex.Completable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import org.slf4j.LoggerFactory
import zalora.com.twitsplit.R
import zalora.com.twitsplit.databinding.FragmentTwitSplitBinding
import zalora.com.twitsplit.databinding.TweetMessageItemBinding
import zalora.com.twitsplit.di.Injectable
import zalora.com.twitsplit.persistence.Tweet
import zalora.com.twitsplit.ui.presenter.TwitSplitPresenter
import zalora.com.twitsplit.ui.viewmodel.TweetsViewModel
import zalora.com.twitsplit.utils.TwitSplitString
import zalora.com.twitsplit.utils.Utils
import kotlinx.android.synthetic.main.fragment_twit_split.*
import zalora.com.twitsplit.persistence.TweetDao
import javax.inject.Inject

class TwitSplitFragment : Fragment(), Injectable, TwitSplitPresenter {

    companion object {
        fun newInstance() = TwitSplitFragment()
    }

    private val LOG = LoggerFactory.getLogger(TwitSplitFragment::class.java)

    @Inject
    lateinit var viewModelFactory: ViewModelProvider.Factory

    @Inject
    lateinit var viewModel: TweetsViewModel

    @Inject
    lateinit var twitSplitString: TwitSplitString

    @Inject
    lateinit var tweetDao: TweetDao

    private lateinit var binding: FragmentTwitSplitBinding

    @Inject
    lateinit var disposable: CompositeDisposable


    private val adapter = TweetAdapter()


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
            DataBindingUtil.inflate<FragmentTwitSplitBinding>(inflater, R.layout.fragment_twit_split, container, false).also {
                binding = it
            }.root


    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        viewModel = ViewModelProviders.of(activity!!, viewModelFactory).get(TweetsViewModel::class.java)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.layoutManager = LinearLayoutManager(activity)
        binding.presenter = this
        val observer: Observer<MutableList<Tweet>> = Observer { it ->
            adapter.run {
                Utils.hideKeyboard(binding.messageEditText.context)
                items.clear()
                items.addAll(ArrayList(it))
                notifyDataSetChanged()
            }
        }

        viewModel.tweets.observe(this, observer)

        viewModel.fetchTweets()

    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.twit_split_menus, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.remove_all -> {
                disposable.add(Completable.fromAction({
                    tweetDao.deleteAll()
                }).subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread()).subscribe {

                        })
                return true
            }
            R.id.about -> {
                Toast.makeText(context, R.string.about, Toast.LENGTH_LONG).show()
                return true
            }

            R.id.clear_text -> {
                clearText()
                return true
            }
        }
        return super.onOptionsItemSelected(item)

    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onPause() {
        super.onPause()
        viewModel.lastMessage.value = binding.messageEditText.text.toString()
    }


    override fun onResume() {
        super.onResume()
        if (viewModel.lastMessage.value!!.isNotEmpty()) {
            var text = viewModel.lastMessage.value
            binding.messageEditText.text = Editable.Factory.getInstance().newEditable(text)
            binding.messageEditText.setSelection(text!!.length)
        }
    }

    override fun sendMessages(view: View) {
        val text = binding.messageEditText.text.toString()
        var valid: Boolean = twitSplitString.isValidMessages(text, TwitSplitString.LIMIT_CHARACTERS)
        if (!valid) {
            Toast.makeText(context, R.string.invalid_message, Toast.LENGTH_LONG).show()
        } else {
            if (twitSplitString.isSingleLine(text, TwitSplitString.LIMIT_CHARACTERS)) {
                LOG.debug("Single Message")
                send_button.isEnabled = false
                Utils.hideKeyboard(send_button.context)
                disposable.add(Completable.fromAction {
                    tweetDao.insertTweet(Tweet.buildTweet(text))
                }.subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread()).subscribe {
                    clearText()
                    viewModel.tweets.value!!.add(0,Tweet.buildTweet(text))
                    send_button.isEnabled = true
                    LOG.debug("Insert Single Messages DONE")
                })
            } else {
                LOG.debug("Multi Messages")
                send_button.isEnabled = false
                Utils.hideKeyboard(send_button.context)
                val estimateLines = twitSplitString.estimateLines(text, TwitSplitString.LIMIT_CHARACTERS)
                val lines = twitSplitString.splitMessage(twitSplitString.getWordArray(text), estimateLines, TwitSplitString.LIMIT_CHARACTERS)
                val tweets = mutableListOf<Tweet>()
                disposable.add(Completable.fromAction {
                    lines.forEach { it ->
                        tweets.add(Tweet.buildTweet(it))
                    }
                    tweetDao.insertTweets(tweets)
                }.subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread()).subscribe {
                    clearText()
                    viewModel.tweets.value!!.addAll(0, tweets)
                    send_button.isEnabled = true
                    LOG.debug("Insert Multi Messages DONE")
                })
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        disposable.clear()
    }

    private fun clearText() {
        binding.messageEditText.text = Editable.Factory.getInstance().newEditable("")
    }
}

class TweetAdapter : RecyclerView.Adapter<TweetAdapter.ViewHolder>() {
    val items = ArrayList<Tweet>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TweetAdapter.ViewHolder {
        return ViewHolder(DataBindingUtil.inflate(LayoutInflater.from(parent.context), R.layout.tweet_message_item, parent, false))
    }

    override fun onBindViewHolder(holder: TweetAdapter.ViewHolder, position: Int) {
        holder.binding.tweet = items[position]
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(val binding: TweetMessageItemBinding) : RecyclerView.ViewHolder(binding.root)
}
