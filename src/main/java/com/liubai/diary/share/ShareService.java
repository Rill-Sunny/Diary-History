package com.liubai.diary.share;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.liubai.diary.config.ApiException;
import com.liubai.diary.journal.JournalService;
import com.liubai.diary.media.MediaService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

@Service
public class ShareService {
    private static final SecureRandom RANDOM=new SecureRandom();
    private final ShareMapper shares;
    private final JournalService journals;
    private final MediaService media;
    private final ObjectMapper json;
    private final String baseUrl;
    public ShareService(ShareMapper shares,JournalService journals,MediaService media,ObjectMapper json,
                        @Value("${app.base-url:http://localhost:5173}") String baseUrl) {
        this.shares=shares;this.journals=journals;this.media=media;this.json=json;this.baseUrl=baseUrl.replaceAll("/+$","");
    }
    @Transactional
    public ShareRequest submit(long ownerId,long journalId,int days) {
        if (days<1||days>90) throw ApiException.badRequest("分享有效期须为 1 至 90 天。");
        lockJournal(ownerId,journalId);
        JournalService.JournalResponse journal=journals.get(ownerId,journalId);
        ShareMapper.LinkRow link=shares.linkForJournal(journalId);
        shares.closeOlderPending(ownerId,journalId);
        ShareMapper.RequestInput input=new ShareMapper.RequestInput();
        input.setOwnerId(ownerId);input.setJournalId(journalId);input.setJournalVersion(journal.version());
        input.setTitle(journal.title());input.setJournalDate(journal.journalDate());
        input.setContentJson(journal.content().toString());input.setPlainText(journal.plainText());input.setValidityDays(days);
        input.setUpdatesExistingLink(isActive(link));
        shares.insert(input);
        ShareMapper.UserRequestRow row=shares.listOwner(ownerId).stream().filter(item->item.id()==input.getId()).findFirst().orElseThrow(ApiException::notFound);
        return requestResponse(row);
    }
    public List<ShareRequest> listOwner(long ownerId) { return shares.listOwner(ownerId).stream().map(this::requestResponse).toList(); }
    public List<ShareRequest> reviewQueue() {
        return shares.queue().stream().map(row->new ShareRequest(row.id(),row.journalId(),row.journalTitle(),row.status(),row.submittedAt(),null,null,null)).toList();
    }
    public ReviewSnapshot reviewSnapshot(long requestId) {
        ShareMapper.RequestRow row=shares.findById(requestId);
        if (row==null) throw ApiException.notFound();
        return snapshot(row,"/api/admin/reviews/"+requestId+"/media/");
    }
    @Transactional
    public void approve(long requestId,long reviewerId) {
        ShareMapper.RequestRow row=shares.findById(requestId);
        if (row==null) throw ApiException.notFound();
        lockJournal(row.ownerId(),row.journalId());
        row=shares.lockRequest(requestId);
        if (row==null) throw ApiException.notFound();
        ShareMapper.RequestRow newest=shares.newestPending(row.journalId());
        if (newest==null||newest.id()!=requestId) throw ApiException.conflict("这条申请已被更新的提交取代，请刷新审核队列。");
        ShareMapper.LinkRow link=shares.linkForJournal(row.journalId());
        if (row.updatesExistingLink() && !isActive(link))
            throw ApiException.conflict("原分享链接已失效，请作者重新发起分享。");
        if (shares.approve(requestId,reviewerId)!=1) throw ApiException.conflict("这条申请已被其他管理员处理。");
        LocalDateTime now=LocalDateTime.now(ZoneOffset.UTC);
        if (isActive(link)) {
            if (shares.moveActiveLink(row.journalId(),requestId)!=1) throw ApiException.conflict("分享链接状态已变化，请刷新审核队列。");
        } else {
            String token=newToken();
            LocalDateTime expiration=now.plusDays(row.validityDays());
            if (link==null) shares.insertLink(row.ownerId(),row.journalId(),token,requestId,expiration);
            else shares.renewLink(row.journalId(),token,requestId,expiration);
        }
    }
    @Transactional
    public void reject(long requestId,long reviewerId,String reason) {
        if (reason==null||reason.isBlank()) throw ApiException.badRequest("请填写拒绝原因。");
        if (reason.length()>500) reason=reason.substring(0,500);
        if (shares.reject(requestId,reviewerId,reason.strip())!=1) throw ApiException.conflict("这条申请已被其他管理员处理。");
    }
    @Transactional
    public void revoke(long ownerId,long requestId) {
        ShareMapper.RequestRow request=shares.findOwned(requestId,ownerId);
        if (request==null) throw ApiException.notFound();
        lockJournal(ownerId,request.journalId());
        request=shares.lockRequest(requestId);
        if (request==null) throw ApiException.notFound();
        ShareMapper.LinkRow link=shares.linkForJournal(request.journalId());
        if (link!=null && link.currentRequestId()==requestId) {
            shares.revokeLink(request.journalId());
            shares.closeOlderPending(ownerId,request.journalId());
        }
        if (shares.revokeRequest(requestId,ownerId)!=1) throw ApiException.conflict("这条申请已处理或已撤销。");
    }
    public ReviewSnapshot read(String token) {
        ShareMapper.LinkRow link=activeLink(token);
        ShareMapper.RequestRow row=shares.approvedSnapshot(link.currentRequestId());
        if (row==null) throw ApiException.notFound();
        return snapshot(row,"/api/shares/read/"+token+"/media/"+row.id()+"/");
    }
    public byte[] readMedia(String token,long requestId,int position) {
        ShareMapper.LinkRow link=activeLink(token);
        if (link.currentRequestId()!=requestId) throw ApiException.notFound();
        ShareMapper.RequestRow row=shares.approvedSnapshot(link.currentRequestId());
        return processedImage(row,position);
    }
    public byte[] reviewMedia(long requestId,int position) {
        return processedImage(shares.findById(requestId),position);
    }
    private boolean isActive(ShareMapper.LinkRow link) {
        return link!=null && link.revokedAt()==null && link.expiresAt().isAfter(LocalDateTime.now(ZoneOffset.UTC));
    }
    private void lockJournal(long ownerId,long journalId) {
        if(shares.lockOwner(ownerId)==null || shares.lockJournal(journalId,ownerId)==null) throw ApiException.notFound();
    }
    private ShareMapper.LinkRow activeLink(String token) {
        if (token==null || !token.matches("[a-f0-9]{64}")) throw ApiException.notFound();
        ShareMapper.LinkRow link=shares.activeLink(token);
        if (link==null) throw ApiException.notFound();
        return link;
    }
    private List<ObjectNode> imageAttributes(JsonNode node) {
        List<ObjectNode> images=new ArrayList<>();
        collectImages(node,images);
        return images;
    }
    private void collectImages(JsonNode node,List<ObjectNode> images) {
        if ("image".equals(node.path("type").asText()) && node.get("attrs") instanceof ObjectNode attrs)
            images.add(attrs);
        for (JsonNode child:node.path("content")) collectImages(child,images);
    }
    private byte[] processedImage(ShareMapper.RequestRow row,int position) {
        if (row==null || position<0) throw ApiException.notFound();
        List<ObjectNode> images=imageAttributes(parseContent(row));
        if (position>=images.size()) throw ApiException.notFound();
        ObjectNode attrs=images.get(position);
        MediaService.StoredFile original=media.get(row.ownerId(),attrs.path("mediaId").asLong());
        return ShareImage.render(original.path(),attrs.path("cropped").asBoolean(),attrs.path("rotation").asInt());
    }
    private ReviewSnapshot snapshot(ShareMapper.RequestRow row,String mediaPrefix) {
        JsonNode content=parseContent(row);
        List<String> urls=new ArrayList<>();
        for (ObjectNode attrs:imageAttributes(content)) {
            String url=mediaPrefix+urls.size();
            urls.add(url);
            attrs.put("src",url).put("rotation",0).put("cropped",false).remove("mediaId");
        }
        return new ReviewSnapshot(row.id(),row.journalTitle(),row.journalDate(),row.journalVersion(),
                row.submittedAt(),content,row.plainText(),urls,row.status(),row.reviewReason());
    }
    private JsonNode parseContent(ShareMapper.RequestRow row) {
        try { return json.readTree(row.contentJson()); }
        catch (Exception e) { throw new IllegalStateException("Stored share snapshot is invalid",e); }
    }
    private ShareRequest requestResponse(ShareMapper.UserRequestRow row) {
        String status=row.status();
        String url=null;
        if (row.tokenValue()!=null && row.expiresAt()!=null) url=baseUrl+"/shared/"+row.tokenValue();
        else if ("APPROVED".equals(status)) status="EXPIRED";
        return new ShareRequest(row.id(),row.journalId(),row.journalTitle(),status,row.submittedAt(),row.expiresAt(),url,row.reviewReason());
    }
    private String newToken() {
        byte[] bytes=new byte[32];RANDOM.nextBytes(bytes);return HexFormat.of().formatHex(bytes);
    }
    public record ShareRequest(long id,long journalId,String journalTitle,String status,LocalDateTime submittedAt,
                               LocalDateTime expiresAt,String shareUrl,String reviewReason) {}
    public record ReviewSnapshot(long id,String journalTitle,java.time.LocalDate journalDate,long versionNumber,
                                 LocalDateTime submittedAt,JsonNode content,String plainText,List<String> imageUrls,
                                 String status,String reviewReason) {}
}
