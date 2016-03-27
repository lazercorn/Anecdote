package io.gresse.hugo.anecdote.service;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.util.Log;

import com.squareup.otto.Subscribe;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.jsoup.select.Selector;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.gresse.hugo.anecdote.event.BusProvider;
import io.gresse.hugo.anecdote.event.Event;
import io.gresse.hugo.anecdote.event.LoadNewAnecdoteEvent;
import io.gresse.hugo.anecdote.event.OnAnecdoteLoadedEvent;
import io.gresse.hugo.anecdote.event.RequestFailedEvent;
import io.gresse.hugo.anecdote.event.network.NetworkConnectivityChangeEvent;
import io.gresse.hugo.anecdote.model.Anecdote;
import io.gresse.hugo.anecdote.model.RichContent;
import io.gresse.hugo.anecdote.model.Website;
import io.gresse.hugo.anecdote.util.NetworkConnectivityListener;
import io.gresse.hugo.anecdote.util.Utils;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * A generic service for all that will load anecdote
 * <p/>
 * Created by Hugo Gresse on 13/02/16.
 */
public class AnecdoteService {

    protected Context              mContext;
    protected OkHttpClient         mOkHttpClient;
    protected String               mServiceName;
    protected Website              mWebsite;
    protected List<Anecdote>       mAnecdotes;
    protected List<Event>          mFailEvents;
    protected Map<Integer, String> mPaginationMap;
    protected boolean mEnd = false;

    public AnecdoteService(Context context, Website website) {
        mContext = context;
        mWebsite = website;
        mServiceName = mWebsite.name.replaceAll("\\s", "") + AnecdoteService.class.getSimpleName();

        mOkHttpClient = new OkHttpClient();
        mAnecdotes = new ArrayList<>();
        mFailEvents = new ArrayList<>();
        mPaginationMap = new HashMap<>();
    }

    /**
     * Get the Website object
     * @return website object
     */
    public Website getWebsite(){
        return mWebsite;
    }

    /**
     * Return the list of anecdotes already loaded by the service
     *
     * @return list of anecdote
     */
    public List<Anecdote> getAnecdotes() {
        return mAnecdotes;
    }

    /**
     * Remvoe all anecdotes
     */
    public void cleanAnecdotes() {
        mAnecdotes.clear();
    }

    /**
     * Retry to send failed event
     */
    public void retryFailedEvent() {
        if (!mFailEvents.isEmpty()) {
            for (Event event : mFailEvents) {
                BusProvider.getInstance().post(event);
            }
            mFailEvents.clear();
        }
    }

    /**
     * Download the given page and parse it
     *
     * @param event      original event
     * @param pageNumber the page to download
     */
    private void downloadLatest(@NonNull final Event event, final int pageNumber) {
        Log.d(mServiceName, "Downloading page " + pageNumber);
        Request request;
        try {
            request = new Request.Builder()
                    .url(mWebsite.getPageUrl(pageNumber, mPaginationMap))
                    .header("User-Agent", Utils.getUserAgent())
                    .build();
        } catch (IllegalArgumentException exception) {
            mFailEvents.add(event);
            postOnUiThread(new RequestFailedEvent(
                    mWebsite.id,
                    "Website configuration is wrong: " + mWebsite.name,
                    exception,
                    pageNumber));
            return;
        }

        mOkHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                e.printStackTrace();
                mFailEvents.add(event);
                postOnUiThread(new RequestFailedEvent(
                        mWebsite.id, "Unable to load " + mWebsite.name,
                        e,
                        pageNumber));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                // We are not on main thread
                if (response.isSuccessful()) {
                    try {
                        processResponse(pageNumber, response);
                    } catch (Selector.SelectorParseException exception) {
                        postOnUiThread(new RequestFailedEvent(
                                mWebsite.id,
                                "Something went wrong, try another website setting",
                                exception,
                                pageNumber));
                    }
                } else {
                    mFailEvents.add(event);
                    postOnUiThread(new RequestFailedEvent(
                            mWebsite.id,
                            "Unable to load website",
                            null,
                            pageNumber));
                }

            }
        });
    }


    private void processResponse(final int pageNumber, Response response) {
        Document document;
        try {
            document = Jsoup.parse(response.body().string());
        } catch (IOException e) {
            response.body().close();
            postOnUiThread(new RequestFailedEvent(mWebsite.id, "Unable to parse " + mWebsite.name + " website", null, pageNumber));
            return;
        } finally {
            response.body().close();
        }

        final Elements elements = document.select(mWebsite.selector);

        if (elements != null && !elements.isEmpty()) {
            Element tempElement = null;
            String content;
            String url;
            RichContent richContent = null;

            /**
             * We get each item to select the correct data and apply the WebsiteItem options (replace, prefix, etc).
             * We pass the first parameter to the getData to not create a new Object each time
             */
            for (Element element : elements) {
                //noinspection ConstantConditions
                content = mWebsite.contentItem.getData(element, tempElement);
                //noinspection ConstantConditions
                url = mWebsite.urlItem.getData(element, tempElement);

                if(mWebsite.hasAdditionalContent()){
                    //noinspection ConstantConditions
                    richContent = mWebsite.additionalMixedContentItem.getRichData(element, tempElement);
                }

                mAnecdotes.add(new Anecdote(content, url, richContent));
            }

            if (mWebsite.paginationItem != null) {
                mPaginationMap.put(pageNumber + 1, mWebsite.paginationItem.getData(document));
            }

            postOnUiThread(new OnAnecdoteLoadedEvent(mWebsite.id, elements.size(), pageNumber));
        } else {
            Log.d(mServiceName, "No elements from this");
            mEnd = true;
        }
    }

    /**
     * Post an Event ot UI Thread
     *
     * @param event the event to post on Bus
     */
    protected void postOnUiThread(final Event event) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                BusProvider.getInstance().post(event);
            }
        });
    }

    /***************************
     * Event
     ***************************/

    @Subscribe
    public void loadNextAnecdoteEvent(LoadNewAnecdoteEvent event) {
        if (event.websiteId != mWebsite.id) return;

        int page = 1;
        int estimatedCurrentPage = event.start / mWebsite.itemPerPage;
        if (estimatedCurrentPage >= 1) {
            page += estimatedCurrentPage;
        }
        // Log.d(TAG, "loadNexAnecdoteEvent start:" + event.start + " page:" + page);
        downloadLatest(event, page);
    }


    /**
     * Called by child service
     *
     * @param connectivityEvent an event fired when the network connectivity change
     */
    @Subscribe
    public void onConnectivityChangeListener(NetworkConnectivityChangeEvent connectivityEvent) {
        if (connectivityEvent.state != NetworkConnectivityListener.State.CONNECTED) {
            return;
        }
        retryFailedEvent();
    }
}
