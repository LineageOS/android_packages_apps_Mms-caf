/*
 * Copyright (C) 2008 Esmertec AG.
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.mms.model;


import com.android.mms.presenters.SlideShowPresenter;
import com.google.android.mms.ContentType;
import com.google.android.mms.MmsException;
import com.google.android.mms.pdu.GenericPdu;
import com.google.android.mms.pdu.MultimediaMessagePdu;
import com.google.android.mms.pdu.PduBody;
import com.google.android.mms.pdu.PduHeaders;
import com.google.android.mms.pdu.PduPart;
import com.google.android.mms.pdu.PduPersister;

import com.android.mms.ContentRestrictionException;
import com.android.mms.ExceedMessageSizeException;
import com.android.mms.LogTag;
import com.android.mms.MmsConfig;
import com.android.mms.UnsupportContentTypeException;
import com.android.mms.dom.smil.parser.SmilXmlSerializer;
import com.android.mms.layout.LayoutManager;
import com.android.mms.ui.Presenter;

import org.w3c.dom.NodeList;
import org.w3c.dom.smil.SMILDocument;
import org.w3c.dom.smil.SMILElement;
import org.w3c.dom.smil.SMILLayoutElement;
import org.w3c.dom.smil.SMILMediaElement;
import org.w3c.dom.smil.SMILParElement;
import org.w3c.dom.smil.SMILRegionElement;
import org.w3c.dom.smil.SMILRootLayoutElement;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

public class SlideshowModel extends Model
        implements List<MediaModel>, IModelChangedObserver {
    private static final String TAG = LogTag.TAG;

    private final LayoutModel mLayout;
    private final ArrayList<MediaModel> mSlides;
    private SMILDocument mDocumentCache;
    private PduBody mPduBodyCache;
    private int mCurrentMessageSize;    // This is the current message size, not including
                                        // attachments that can be resized (such as photos)
    private int mTotalMessageSize;      // This is the computed total message size
    private int mSubjectSize;           // This is subject size
    Context mContext;

    // amount of space to leave in a slideshow for overhead.
    public static final int SLIDESHOW_SLOP = 1024;
    private final SlideShowPresenter mPresenter;

    private SlideshowModel(Context context) {
        mLayout = new LayoutModel();
        mSlides = new ArrayList<MediaModel>();
        mContext = context;
        mPresenter = new SlideShowPresenter(mContext, this);
    }

    private SlideshowModel (
            LayoutModel layouts, ArrayList<MediaModel> slides,
            SMILDocument documentCache, PduBody pbCache,
            Context context) {
        mLayout = layouts;
        mSlides = slides;
        mContext = context;
        mDocumentCache = documentCache;
        mPduBodyCache = pbCache;
        mPresenter = new SlideShowPresenter(mContext, this);
        for (MediaModel slide : mSlides) {
            increaseMessageSize(slide.getMediaSize());
        }
    }

    public static SlideshowModel createNew(Context context) {
        return new SlideshowModel(context);
    }

    public static SlideshowModel createFromMessageUri(
            Context context, Uri uri) throws MmsException {
        return createFromPduBody(context, getPduBody(context, uri));
    }

    public static SlideshowModel createFromPduBody(Context context, PduBody pb) throws MmsException {
        SMILDocument document = SmilHelper.getDocument(pb);

        // Create root-layout model.
        SMILLayoutElement sle = document.getLayout();
        SMILRootLayoutElement srle = sle.getRootLayout();
        int w = srle.getWidth();
        int h = srle.getHeight();
        if ((w == 0) || (h == 0)) {
            w = LayoutManager.getInstance().getLayoutParameters().getWidth();
            h = LayoutManager.getInstance().getLayoutParameters().getHeight();
            srle.setWidth(w);
            srle.setHeight(h);
        }
        RegionModel rootLayout = new RegionModel(
                null, 0, 0, w, h);

        // Create region models.
        ArrayList<RegionModel> regions = new ArrayList<RegionModel>();
        NodeList nlRegions = sle.getRegions();
        int regionsNum = nlRegions.getLength();

        for (int i = 0; i < regionsNum; i++) {
            SMILRegionElement sre = (SMILRegionElement) nlRegions.item(i);
            RegionModel r = new RegionModel(sre.getId(), sre.getFit(),
                    sre.getLeft(), sre.getTop(), sre.getWidth(), sre.getHeight(),
                    sre.getBackgroundColor());
            regions.add(r);
        }
        LayoutModel layouts = new LayoutModel(rootLayout, regions);

        // Create slide models.
        SMILElement docBody = document.getBody();
        NodeList slideNodes = docBody.getChildNodes();
        int slidesNum = slideNodes.getLength();
        ArrayList<MediaModel> slides = new ArrayList<MediaModel>(slidesNum);
        int totalMessageSize = 0;

        for (int i = 0; i < slidesNum; i++) {
            // FIXME: This is NOT compatible with the SMILDocument which is
            // generated by some other mobile phones.
            SMILParElement par = (SMILParElement) slideNodes.item(i);

            // Create media models for each slide.
            NodeList mediaNodes = par.getChildNodes();
            int mediaNum = mediaNodes.getLength();

            for (int j = 0; j < mediaNum; j++) {
                SMILMediaElement sme = (SMILMediaElement) mediaNodes.item(j);
                try {
                    MediaModel media = MediaModelFactory.getMediaModel(
                            context, sme, layouts, pb);
                    slides.add(media);
                    totalMessageSize += media.getMediaSize();
                } catch (IOException e) {
                    Log.e(TAG, e.getMessage(), e);
                } catch (IllegalArgumentException e) {
                    Log.e(TAG, e.getMessage(), e);
                } catch (UnsupportContentTypeException e) {
                    Log.e(TAG, e.getMessage(), e);
                }
            }
        }

        SlideshowModel slideshow = new SlideshowModel(layouts, slides, document, pb, context);
        slideshow.mTotalMessageSize = totalMessageSize;
        slideshow.registerModelChangedObserver(slideshow);
        return slideshow;
    }

    public PduBody toPduBody() {
        if (mPduBodyCache == null) {
            mDocumentCache = SmilHelper.getDocument(this);
            mPduBodyCache = makePduBody(mDocumentCache);
        }
        return mPduBodyCache;
    }

    private PduBody makePduBody(SMILDocument document) {
        PduBody pb = new PduBody();

        boolean hasForwardLock = false;
        for (MediaModel media : mSlides) {
            PduPart part = new PduPart();

            if (media.isText()) {
                TextModel text = (TextModel) media;
                // Don't create empty text part.
                if (TextUtils.isEmpty(text.getText())) {
                    continue;
                }
                // Set Charset if it's a text media.
                part.setCharset(text.getCharset());
            }

            // Set Content-Type.
            part.setContentType(media.getContentType().getBytes());

            String src = media.getSrc();
            String location;
            boolean startWithContentId = src.startsWith("cid:");
            if (startWithContentId) {
                location = src.substring("cid:".length());
            } else {
                location = src;
            }

            // Set Content-Location.
            part.setContentLocation(location.getBytes());

            // Set Content-Id.
            if (startWithContentId) {
                //Keep the original Content-Id.
                part.setContentId(location.getBytes());
            }
            else {
                int index = location.lastIndexOf(".");
                String contentId = (index == -1) ? location
                        : location.substring(0, index);
                part.setContentId(contentId.getBytes());
            }

            if (media.isText()) {
                part.setData(((TextModel) media).getText().getBytes());
            } else if (media.isImage() || media.isVideo() || media.isAudio()
                    || media.isVcard() || media.isVCal()) {
                part.setDataUri(media.getUri());
                if (media.isVcard()) {
                    part.setName(src.getBytes());
                    if (!TextUtils.isEmpty(((VcardModel) media).getLookupUri())) {
                        part.setContentDisposition(
                                ((VcardModel) media).getLookupUri().getBytes());
                    }
                }
            } else {
                if (media.getUri() != null) {
                    part.setDataUri(media.getUri());
                }
                Log.w(TAG, "Unsupport media: " + media);
            }

            pb.addPart(part);
        }

        // Create and insert SMIL part(as the first part) into the PduBody.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SmilXmlSerializer.serialize(document, out);
        PduPart smilPart = new PduPart();
        smilPart.setContentId("smil".getBytes());
        smilPart.setContentLocation("smil.xml".getBytes());
        smilPart.setContentType(ContentType.APP_SMIL.getBytes());
        smilPart.setData(out.toByteArray());
        pb.addPart(0, smilPart);

        return pb;
    }

    public HashMap<Uri, InputStream> openPartFiles(ContentResolver cr) {
        HashMap<Uri, InputStream> openedFiles = null;     // Don't create unless we have to

        for (MediaModel media : mSlides) {
            if (media.isText()) {
                continue;
            }
            Uri uri = media.getUri();
            InputStream is;
            try {
                is = cr.openInputStream(uri);
                if (is != null) {
                    if (openedFiles == null) {
                        openedFiles = new HashMap<Uri, InputStream>();
                    }
                    openedFiles.put(uri, is);
                }
            } catch (FileNotFoundException e) {
                Log.e(TAG, "openPartFiles couldn't open: " + uri, e);
            }
        }
        return openedFiles;
    }

    public PduBody makeCopy() {
        return makePduBody(SmilHelper.getDocument(this));
    }

    public SMILDocument toSmilDocument() {
        if (mDocumentCache == null) {
            mDocumentCache = SmilHelper.getDocument(this);
        }
        return mDocumentCache;
    }

    public static PduBody getPduBody(Context context, Uri msg) throws MmsException {
        PduPersister p = PduPersister.getPduPersister(context);
        GenericPdu pdu = p.load(msg);

        int msgType = pdu.getMessageType();
        if ((msgType == PduHeaders.MESSAGE_TYPE_SEND_REQ)
                || (msgType == PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF)) {
            return ((MultimediaMessagePdu) pdu).getBody();
        } else {
            throw new MmsException();
        }
    }

    public void setCurrentMessageSize(int size) {
        mCurrentMessageSize = size;
    }

    public void setTotalMessageSize(int size) {
        mTotalMessageSize = size;
    }

    // getCurrentMessageSize returns the size of the message, not including resizable attachments
    // such as photos. mCurrentMessageSize is used when adding/deleting/replacing non-resizable
    // attachments (movies, sounds, etc) in order to compute how much size is left in the message.
    // The difference between mCurrentMessageSize and the maxSize allowed for a message is then
    // divided up between the remaining resizable attachments. While this function is public,
    // it is only used internally between various MMS classes. If the UI wants to know the
    // size of a MMS message, it should call getTotalMessageSize() instead.
    public int getCurrentMessageSize() {
        return mCurrentMessageSize;
    }

    // getTotalMessageSize returns the total size of the message, including resizable attachments
    // such as photos. This function is intended to be used by the UI for displaying the size of the
    // MMS message.
    public int getTotalMessageSize() {
        return mTotalMessageSize;
    }

    public void setSubjectSize(int size) {
        mSubjectSize = size;
    }

    public int getSubjectSize() {
        return mSubjectSize;
    }

    public int getRemainMessageSize() {
        int totalMediaSize = 0;
        for (MediaModel media : mSlides) {
            totalMediaSize += media.getMediaSize();
        }
        setTotalMessageSize(totalMediaSize);
        // The totalMediaSize include text size which inputting before.
        // So we don't calculate text size again.
        int remainSize = MmsConfig.getMaxMessageSize() - getSMILSize() - totalMediaSize
                - mSubjectSize;
        return remainSize < SLIDESHOW_SLOP ? 0 : remainSize - SLIDESHOW_SLOP;
    }

    /*
     * Get SMIL size when create and edit MMS. Not used for received MMS.
     */
    public int getSMILSize() {
        // first pdu part is SMIL
        return toPduBody().getPart(0).getData().length;
    }

    /*
     * getTotalTextMessageSize returns the total text size of the MMS.
     */
    public int getTotalTextMessageSize() {
        int textSize = 0;
        if (mSlides.size() > 0) {
            for (MediaModel slide : mSlides) {
                if (slide instanceof TextModel) {
                    textSize += slide.getMediaSize();
                }
            }
        }
        return textSize;
    }

    public void increaseMessageSize(int increaseSize) {
        if (increaseSize > 0) {
            mCurrentMessageSize += increaseSize;
        }
    }

    public void decreaseMessageSize(int decreaseSize) {
        if (decreaseSize > 0) {
            mCurrentMessageSize -= decreaseSize;
        }
    }

    public LayoutModel getLayout() {
        return mLayout;
    }

    //
    // Implement List<E> interface.
    //
    public boolean add(MediaModel object) {
        int increaseSize = object.getMediaSize();
        checkMessageSize(increaseSize);

        if ((object != null) && mSlides.add(object)) {
            increaseMessageSize(increaseSize);
            object.registerModelChangedObserver(this);
            for (IModelChangedObserver observer : mModelChangedObservers) {
                object.registerModelChangedObserver(observer);
            }
            notifyModelChanged(true);
            return true;
        }
        return false;
    }

    public boolean addAll(Collection<? extends MediaModel> collection) {
        throw new UnsupportedOperationException("Operation not supported.");
    }

    public void clear() {
        if (mSlides.size() > 0) {
            for (MediaModel slide : mSlides) {
                slide.unregisterModelChangedObserver(this);
                for (IModelChangedObserver observer : mModelChangedObservers) {
                    slide.unregisterModelChangedObserver(observer);
                }
            }
            mCurrentMessageSize = 0;
            mSlides.clear();
            notifyModelChanged(true);
        }
    }

    public boolean contains(Object object) {
        return mSlides.contains(object);
    }

    public boolean containsAll(Collection<?> collection) {
        return mSlides.containsAll(collection);
    }

    public boolean isEmpty() {
        return mSlides.isEmpty();
    }

    public Iterator<MediaModel> iterator() {
        return mSlides.iterator();
    }

    public boolean remove(Object object) {
        if ((object != null) && mSlides.remove(object)) {
            MediaModel slide = (MediaModel) object;
            decreaseMessageSize(slide.getMediaSize());
            slide.unregisterAllModelChangedObservers();
            notifyModelChanged(true);
            return true;
        }
        return false;
    }

    public boolean removeAll(Collection<?> collection) {
        throw new UnsupportedOperationException("Operation not supported.");
    }

    public boolean retainAll(Collection<?> collection) {
        throw new UnsupportedOperationException("Operation not supported.");
    }

    public int size() {
        return mSlides.size();
    }

    public Object[] toArray() {
        return mSlides.toArray();
    }

    public <T> T[] toArray(T[] array) {
        return mSlides.toArray(array);
    }

    public void add(int location, MediaModel object) {
        if (object != null) {
            int increaseSize = object.getMediaSize();
            checkMessageSize(increaseSize);

            mSlides.add(location, object);
            increaseMessageSize(increaseSize);
            object.registerModelChangedObserver(this);
            for (IModelChangedObserver observer : mModelChangedObservers) {
                object.registerModelChangedObserver(observer);
            }
            notifyModelChanged(true);
        }
    }

    public boolean addAll(int location,
            Collection<? extends MediaModel> collection) {
        throw new UnsupportedOperationException("Operation not supported.");
    }

    public MediaModel get(int location) {
        return (location >= 0 && location < mSlides.size()) ? mSlides.get(location) : null;
    }

    public int indexOf(Object object) {
        return mSlides.indexOf(object);
    }

    public int lastIndexOf(Object object) {
        return mSlides.lastIndexOf(object);
    }

    public ListIterator<MediaModel> listIterator() {
        return mSlides.listIterator();
    }

    public ListIterator<MediaModel> listIterator(int location) {
        return mSlides.listIterator(location);
    }

    public MediaModel remove(int location) {
        MediaModel slide = mSlides.remove(location);
        if (slide != null) {
            decreaseMessageSize(slide.getMediaSize());
            slide.unregisterAllModelChangedObservers();
            notifyModelChanged(true);
        }
        return slide;
    }

    public MediaModel set(int location, MediaModel object) {
        MediaModel slide = mSlides.get(location);
        if (null != object) {
            int removeSize = 0;
            int addSize = object.getMediaSize();
            if (null != slide) {
                removeSize = slide.getMediaSize();
            }
            if (addSize > removeSize) {
                checkMessageSize(addSize - removeSize);
                increaseMessageSize(addSize - removeSize);
            } else {
                decreaseMessageSize(removeSize - addSize);
            }
        }

        slide =  mSlides.set(location, object);
        if (slide != null) {
            slide.unregisterAllModelChangedObservers();
        }

        if (object != null) {
            object.registerModelChangedObserver(this);
            for (IModelChangedObserver observer : mModelChangedObservers) {
                object.registerModelChangedObserver(observer);
            }
        }

        notifyModelChanged(true);
        return slide;
    }

    public List<MediaModel> subList(int start, int end) {
        return mSlides.subList(start, end);
    }

    @Override
    protected void registerModelChangedObserverInDescendants(
            IModelChangedObserver observer) {
        mLayout.registerModelChangedObserver(observer);

        for (MediaModel slide : mSlides) {
            slide.registerModelChangedObserver(observer);
        }
    }

    @Override
    protected void unregisterModelChangedObserverInDescendants(
            IModelChangedObserver observer) {
        mLayout.unregisterModelChangedObserver(observer);

        for (MediaModel slide : mSlides) {
            slide.unregisterModelChangedObserver(observer);
        }
    }

    @Override
    protected void unregisterAllModelChangedObserversInDescendants() {
        mLayout.unregisterAllModelChangedObservers();

        for (MediaModel slide : mSlides) {
            slide.unregisterAllModelChangedObservers();
        }
    }

    public void onModelChanged(Model model, boolean dataChanged) {
        if (dataChanged) {
            mDocumentCache = null;
            mPduBodyCache = null;
        }
    }

    public void sync(PduBody pb) {
        for (MediaModel media : mSlides) {
            PduPart part = pb.getPartByContentLocation(media.getSrc());
            if (part != null) {
                media.setUri(part.getDataUri());
            }
        }
    }

    public void checkMessageSize(int increaseSize) throws ContentRestrictionException {
        ContentRestriction cr = ContentRestrictionFactory.getContentRestriction();
        cr.checkMessageSize(mCurrentMessageSize, increaseSize, mContext.getContentResolver());
    }

    /**
     * Determines whether this is a "simple" slideshow.
     * Criteria:
     * - Exactly one slide
     * - Exactly one multimedia attachment, but no audio
     * - It can optionally have a caption
    */
    public boolean isSimple() {
        // There must be one (and only one) slide.
        if (size() != 1)
            return false;

        MediaModel slide = get(0);
        if (!isSlideValid(slide))
            return false;

        return true;
    }

    private boolean isSlideValid(MediaModel slide) {
        // The slide must have either an image, video, vcard or vcal and only one of them.
        boolean hasImage = slide instanceof ImageModel;
        boolean hasVideo = slide instanceof VideoModel;
        boolean hasVcard = slide instanceof VcardModel;
        boolean hasVCal = slide instanceof VCalModel;
        int numAttachments = 0;
        if (hasImage) numAttachments++;
        if (hasVideo) numAttachments++;
        if (hasVcard) numAttachments++;
        if (hasVCal) numAttachments++;
        return numAttachments == 1;
    }

    /**
     * Make sure the text in slide 0 is no longer holding onto a reference to the text
     * in the message text box.
     */
    public void prepareForSend() {
        if (size() >= 1) {
            MediaModel text = get(0);
            if (text instanceof TextModel) {
                ((TextModel)text).cloneText();
            }
        }
    }

    /**
     * Resize all the resizeable media objects to fit in the remaining size of the slideshow.
     * This should be called off of the UI thread.
     *
     * @throws MmsException, ExceedMessageSizeException
     */
    public void finalResize(Uri messageUri) throws MmsException, ExceedMessageSizeException {

        // Figure out if we have any media items that need to be resized and total up the
        // sizes of the items that can't be resized.
        int resizableCnt = 0;
        int fixedSizeTotal = 0;
        for (MediaModel media : mSlides) {
            if (media.getMediaResizable()) {
                ++resizableCnt;
            } else {
                fixedSizeTotal += media.getMediaSize();
            }
        }
        if (Log.isLoggable(LogTag.APP, Log.VERBOSE)) {
            Log.v(TAG, "finalResize: original message size: " + getCurrentMessageSize() +
                    " getMaxMessageSize: " + MmsConfig.getMaxMessageSize() +
                    " fixedSizeTotal: " + fixedSizeTotal);
        }
        if (resizableCnt > 0) {
            int remainingSize = MmsConfig.getMaxMessageSize() - fixedSizeTotal - SLIDESHOW_SLOP;
            if (remainingSize <= 0) {
                throw new ExceedMessageSizeException("No room for pictures");
            }
            long messageId = ContentUris.parseId(messageUri);
            int bytesPerMediaItem = remainingSize / resizableCnt;
            // Resize the resizable media items to fit within their byte limit.
            for (MediaModel media : mSlides) {
                if (media.getMediaResizable()) {
                    media.resizeMedia(bytesPerMediaItem, messageId);
                }
            }
            // One last time through to calc the real message size.
            int totalSize = 0;
            for (MediaModel media : mSlides) {
                totalSize += media.getMediaSize();
            }
            if (Log.isLoggable(LogTag.APP, Log.VERBOSE)) {
                Log.v(TAG, "finalResize: new message size: " + totalSize);
            }

            if (totalSize > MmsConfig.getMaxMessageSize()) {
                throw new ExceedMessageSizeException("After compressing pictures, message too big");
            }
            setCurrentMessageSize(totalSize);

            onModelChanged(this, true);     // clear the cached pdu body
            PduBody pb = toPduBody();
            // This will write out all the new parts to:
            //      /data/data/com.android.providers.telephony/app_parts
            // and at the same time delete the old parts.
            PduPersister.getPduPersister(mContext).updateParts(messageUri, pb, null);
        }
    }

    public void updateTotalMessageSize() {
        int totalSize = 0;
        for (MediaModel media : mSlides) {
            totalSize += media.getMediaSize();
        }
        if (Log.isLoggable(LogTag.APP, Log.VERBOSE)) {
            Log.v(TAG, "updateTotalMessageSize: message size: " + totalSize);
        }
        // mTotalMessageSize include resizable attachments, getTotalMessageSize
        // is called by UI for displaying the size of the MMS message, so set
        // mTotalMessageSize here rather than mCurrentMessageSize.
        setTotalMessageSize(totalSize);
    }

    @Override
    public Presenter getPresenter() {
        return mPresenter;
    }

    public void cancelThumbnailLoading() {
        for (MediaModel i : SlideshowModel.this) {
            i.cancelThumbnailLoading();
        }
    }
}