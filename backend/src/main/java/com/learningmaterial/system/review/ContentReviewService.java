package com.learningmaterial.system.review;

import com.learningmaterial.system.common.ApiException;
import com.learningmaterial.system.material.MaterialMapper;
import com.learningmaterial.system.material.MaterialRow;
import com.learningmaterial.system.material.MaterialDtos.PageView;
import com.learningmaterial.system.review.ContentReviewDtos.ReviewView;
import com.learningmaterial.system.review.ContentReviewDtos.ReviewContent;
import com.learningmaterial.system.auth.*;
import com.learningmaterial.system.admin.AdminUserMapper;
import com.learningmaterial.system.material.MaterialDtos.MaterialView;
import com.learningmaterial.system.material.TagMapper;
import com.learningmaterial.system.material.FileStorage;
import com.learningmaterial.system.material.MaterialService.StoredFile;
import com.learningmaterial.system.notification.NotificationMapper;
import com.learningmaterial.system.task.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.List;

@Service
public class ContentReviewService {
    private final ContentReviewMapper reviews;
    private final MaterialMapper materials;
    private final Clock clock;
    private final AdminUserMapper admin;
    private final SessionMapper sessions;
    private final TagMapper tags;
    private final NotificationMapper notifications;
    private final FileStorage storage;
    private final AiTaskMapper tasks;
    private final AiToolCallMapper calls;
    private final OcrAttemptMapper ocr;
    private final ModelAttemptMapper models;
    public ContentReviewService(ContentReviewMapper reviews,MaterialMapper materials,Clock clock,AdminUserMapper admin,SessionMapper sessions,TagMapper tags,NotificationMapper notifications,FileStorage storage,
                                AiTaskMapper tasks,AiToolCallMapper calls,OcrAttemptMapper ocr,ModelAttemptMapper models) {
        this.reviews=reviews;this.materials=materials;this.clock=clock;
        this.admin=admin;this.sessions=sessions;this.tags=tags;this.notifications=notifications;
        this.storage=storage;
        this.tasks=tasks;this.calls=calls;this.ocr=ocr;this.models=models;
    }
    @Transactional
    public StoredFile file(AuthPrincipal actor,long id) {
        Locked locked=lock(actor,id);
        requireView(actor,id);
        MaterialRow material=locked.material();
        if(material.getStorageKey()==null)throw notFound();
        if(!List.of("application/pdf","image/png","image/jpeg").contains(material.getMimeType()))
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,"STORAGE_TYPE_INVALID","文件暂时无法读取");
        StoredFile file=new StoredFile(storage.open(material.getStorageKey()),material.getMimeType(),material.getFileSize());
        audit(actor,locked,"FILE_VIEW","依据同会话正文查看授权读取原文件","PENDING","PENDING");
        return file;
    }
    @Transactional
    public ReviewContent content(AuthPrincipal actor,long id,String reason) {
        Locked locked=lock(actor,id);
        long audit=audit(actor,locked,"CONTENT_VIEW",reason,"PENDING","PENDING");
        return new ReviewContent(Long.toString(id),MaterialView.from(locked.material(),tags.forMaterial(locked.material().getId()),false),
            locked.material().getStorageKey()==null?null:"/api/v1/admin/reviews/"+id+"/file",Long.toString(audit));
    }
    @Transactional
    public ReviewView decide(AuthPrincipal actor,long id,int version,String decision,String reason) {
        if(!List.of("APPROVED","REJECTED").contains(decision))throw invalid();
        Locked locked=lock(actor,id);
        if(locked.review().getVersion()!=version)throw conflict();
        requireView(actor,id);
        LocalDateTime now=now();
        reviews.setAccess(locked.material().getId(),"APPROVED".equals(decision)?"NORMAL":"RESTRICTED",now);
        if("REJECTED".equals(decision)) {
            long owner=locked.material().getUserId(),material=locked.material().getId();
            tasks.cancelForRestrictedMaterial(owner,material,now);
            calls.markRestrictedSourceUnknown(owner,material,now);
            ocr.markRestrictedSourceUnknown(owner,material,now);
            models.markRestrictedSourceUnknown(owner,material,now);
        }
        if(reviews.decide(id,version,decision,reason,now)!=1)throw conflict();
        audit(actor,locked,"REVIEW_DECISION",reason,"PENDING",decision);
        notifications.insertReviewEvent(locked.material().getUserId(),id,locked.material().getId(),now);
        return ReviewView.from(reviews.find(id));
    }
    private record Locked(MaterialRow material,ContentReviewRow review) { }
    private Locked lock(AuthPrincipal actor,long id) {
        if(admin.lockControl()==null)throw new IllegalStateException("Administrator control row missing");
        UserRow user=reviews.lockActor(actor.userId());
        ContentReviewRow snapshot=reviews.find(id);
        if(snapshot==null)throw notFound();
        MaterialRow material=reviews.lockMaterial(snapshot.getMaterialId());
        ContentReviewRow review=reviews.lockReview(id);
        SessionRow session=sessions.findByIdForUpdate(actor.sessionId(),actor.userId());
        // Read the clock only after the final row lock returns: it may have waited past expiry.
        LocalDateTime now=now();
        if(user==null||!"ENABLED".equals(user.getStatus())||session==null||session.getRevokedAt()!=null||!session.getExpiresAt().isAfter(now))
            throw new ApiException(HttpStatus.UNAUTHORIZED,"UNAUTHORIZED","请重新登录");
        if(!"ADMIN".equals(user.getRole()))throw new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN","没有访问权限");
        if(material==null||review==null)throw notFound();
        if(!"PENDING".equals(review.getStatus())||!"MATERIAL_CONTENT".equals(review.getAccessScope())
            ||"DELETED".equals(material.getStatus())||!material.getVersion().equals(review.getMaterialVersion()))throw conflict();
        return new Locked(material,review);
    }
    private void requireView(AuthPrincipal actor,long id) {
        if(reviews.countContentViews(id,actor.userId(),actor.sessionId())==0)
            throw new ApiException(HttpStatus.FORBIDDEN,"CONTENT_ACCESS_REQUIRED","请先填写理由并查看正文");
    }
    private long audit(AuthPrincipal actor,Locked locked,String action,String reason,String before,String after) {
        ContentReviewMapper.AuditId audit=new ContentReviewMapper.AuditId();
        reviews.audit(audit,actor.userId(),locked.review().getId(),locked.material().getId(),actor.sessionId(),action,reason,before,after,now());
        return audit.getId();
    }
    @Transactional
    public ReviewView submit(long owner,long id,int version,String reason) {
        MaterialRow material=materials.findOwnedForUpdate(id,owner);
        if(material==null)throw notFound();
        if(material.getVersion()!=version || reviews.findVersion(id,version)!=null)throw conflict();
        ContentReviewRow row=new ContentReviewRow();
        row.setMaterialId(id);row.setUserId(owner);row.setMaterialVersion(version);row.setTitle(material.getTitle());
        row.setMaterialType(material.getMaterialType());row.setReason(reason);row.setCreatedAt(now());
        reviews.insert(row);
        return ReviewView.from(reviews.find(row.getId()));
    }
    @Transactional(readOnly=true)
    public PageView<ReviewView> history(long owner,long id,int page,int size) {
        checkPage(page,size);
        if(materials.findOwned(id,owner)==null)throw notFound();
        return page(id,null,page,size);
    }
    @Transactional(readOnly=true)
    public PageView<ReviewView> list(String status,int page,int size) {
        checkPage(page,size);
        status=status==null||status.isBlank()?null:status.trim();
        if(status!=null&&!List.of("PENDING","APPROVED","REJECTED").contains(status))throw invalid();
        return page(null,status,page,size);
    }
    private PageView<ReviewView> page(Long id,String status,int page,int size) {
        return new PageView<>(reviews.list(id,status,size,(long)(page-1)*size).stream().map(ReviewView::from).toList(),page,size,reviews.count(id,status));
    }
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);}
    static void checkPage(int page,int size){if(page<1||size<1||size>100)throw invalid();}
    static ApiException invalid(){return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"INVALID_INPUT","请检查输入字段");}
    static ApiException notFound(){return new ApiException(HttpStatus.NOT_FOUND,"REVIEW_NOT_FOUND","资料或审核记录不存在");}
    static ApiException conflict(){return new ApiException(HttpStatus.CONFLICT,"REVIEW_CONFLICT","资料版本或审核状态已变化，请刷新");}
}
