package org.lightadmin.core.rest;

import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.collect.Lists;
import org.lightadmin.core.config.domain.DomainTypeAdministrationConfiguration;
import org.lightadmin.core.config.domain.GlobalAdministrationConfiguration;
import org.lightadmin.core.config.domain.GlobalAdministrationConfigurationAware;
import org.lightadmin.core.config.domain.scope.ScopeMetadata;
import org.lightadmin.core.config.domain.scope.ScopeMetadataUtils;
import org.lightadmin.core.persistence.metamodel.DomainTypeAttributeMetadata;
import org.lightadmin.core.persistence.metamodel.DomainTypeEntityMetadata;
import org.lightadmin.core.persistence.repository.DynamicJpaRepository;
import org.lightadmin.core.search.SpecificationCreator;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.domain.Specifications;
import org.springframework.data.rest.repository.RepositoryConstraintViolationException;
import org.springframework.data.rest.webmvc.PagingAndSorting;
import org.springframework.data.rest.webmvc.RepositoryRestController;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.PagedResources;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.util.ReflectionUtils;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.UndeclaredThrowableException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Lists.newLinkedList;

@SuppressWarnings( "unchecked" )
@RequestMapping( "/rest" )
public class DynamicRepositoryRestController extends RepositoryRestController implements GlobalAdministrationConfigurationAware {

	private final SpecificationCreator specificationCreator = new SpecificationCreator();

	private GlobalAdministrationConfiguration configuration;

    private ApplicationContext applicationContext;

	@ResponseBody
	@RequestMapping( value = "/{repositoryName}/{id}", method = RequestMethod.GET )
	public ResponseEntity<?> entity(ServletServerHttpRequest request, URI baseUri, @PathVariable String repositoryName, @PathVariable String id) throws IOException {

		final DomainTypeAdministrationConfiguration domainTypeAdministrationConfiguration = configuration.forEntityName( repositoryName );

		final DomainTypeEntityMetadata domainTypeEntityMetadata = domainTypeAdministrationConfiguration.getDomainTypeEntityMetadata();
		final DynamicJpaRepository repository = domainTypeAdministrationConfiguration.getRepository();

		Serializable entityId = _stringToSerializable( id, ( Class<? extends Serializable> ) domainTypeEntityMetadata.getIdAttribute().getType() );

		final Object entity = repository.findOne( entityId );

		return _negotiateResponse( request, HttpStatus.OK, new HttpHeaders(), entity );
	}

	@ResponseBody
	@RequestMapping( value = "/{repositoryName}/scope/{scopeName}/search", method = RequestMethod.GET )
	public ResponseEntity<?> filterEntities( ServletServerHttpRequest request, @SuppressWarnings( "unused" ) URI baseUri, PagingAndSorting pageSort, @PathVariable String repositoryName, @PathVariable String scopeName ) throws IOException {

		final DomainTypeAdministrationConfiguration domainTypeAdministrationConfiguration = configuration.forEntityName( repositoryName );

		final DomainTypeEntityMetadata domainTypeEntityMetadata = domainTypeAdministrationConfiguration.getDomainTypeEntityMetadata();
		final DynamicJpaRepository repository = domainTypeAdministrationConfiguration.getRepository();

		final ScopeMetadata scope = domainTypeAdministrationConfiguration.getScopes().getScope( scopeName );

		final Specification filterSpecification = specificationFromRequest( request, domainTypeEntityMetadata );

		if ( isPredicateScope( scope ) ) {
			final ScopeMetadataUtils.PredicateScopeMetadata predicateScope = ( ScopeMetadataUtils.PredicateScopeMetadata ) scope;

			final Page page = findBySpecificationAndPredicate( repository, filterSpecification, predicateScope.predicate(), pageSort );

			return negotiateResponse( request, page, pageMetadata( page ) );
		}

		if ( isSpecificationScope( scope ) ) {
			final Specification scopeSpecification = ( ( ScopeMetadataUtils.SpecificationScopeMetadata ) scope ).specification();

			Page page = findItemsBySpecification( repository, and( scopeSpecification, filterSpecification ), pageSort );

			return negotiateResponse( request, page, pageMetadata( page ) );
		}

		Page page = findItemsBySpecification( repository, filterSpecification, pageSort );

		return negotiateResponse( request, page, pageMetadata( page ) );
	}

	private Page findBySpecificationAndPredicate( DynamicJpaRepository repository, final Specification specification, Predicate predicate, final PagingAndSorting pageSort ) {
		final List<?> items = findItemsBySpecification( repository, specification, pageSort.getSort() );

		return selectPage( newArrayList( Collections2.filter( items, predicate ) ), pageSort );
	}

	private Page<?> findItemsBySpecification( final DynamicJpaRepository repository, final Specification specification, final PagingAndSorting pageSort ) {
		return repository.findAll( specification, pageSort );
	}

	private List<?> findItemsBySpecification( final DynamicJpaRepository repository, final Specification specification, final Sort sort ) {
		return repository.findAll( specification, sort );
	}

	private Page<?> selectPage( List<Object> items, PagingAndSorting pageSort ) {
		final List<Object> itemsOnPage = items.subList( pageSort.getOffset(), Math.min( items.size(), pageSort.getOffset() + pageSort.getPageSize() ) );

		return new PageImpl<Object>( itemsOnPage, pageSort, items.size() );
	}

	private boolean isSpecificationScope( final ScopeMetadata scope ) {
		return scope instanceof ScopeMetadataUtils.SpecificationScopeMetadata;
	}

	private boolean isPredicateScope( final ScopeMetadata scope ) {
		return scope instanceof ScopeMetadataUtils.PredicateScopeMetadata;
	}

	private Specification and( Specification specification, Specification otherSpecification ) {
		return Specifications.where( specification ).and( otherSpecification );
	}

	private Specification specificationFromRequest( ServletServerHttpRequest request, final DomainTypeEntityMetadata<? extends DomainTypeAttributeMetadata> entityMetadata ) {
		final Map<String, String[]> parameters = request.getServletRequest().getParameterMap();

		return specificationCreator.toSpecification( entityMetadata, parameters );
	}

	private <V extends Serializable> V _stringToSerializable(String str, Class<V> targetType) {
		Method stringToSerializableMethod = ReflectionUtils.findMethod( getClass(), "stringToSerializable", String.class, Class.class );

		ReflectionUtils.makeAccessible( stringToSerializableMethod );

		try {
			return ( V ) stringToSerializableMethod.invoke( this, str, targetType);
		} catch ( InvocationTargetException ex ) {
			ReflectionUtils.rethrowRuntimeException( ex.getTargetException() );
			return null; // :)
		} catch ( IllegalAccessException ex ) {
			throw new UndeclaredThrowableException( ex );
		}
	}

    @ExceptionHandler(RepositoryConstraintViolationException.class)
    @ResponseBody
    public ResponseEntity handleValidationFailure(RepositoryConstraintViolationException ex, ServletServerHttpRequest request) throws IOException {

        Map packet = new HashMap();
        List<Map> errors = new ArrayList<Map>();
        for (FieldError fe : ex.getErrors().getFieldErrors()) {
            List<Object> args = new ArrayList<Object>();
            args.add(fe.getObjectName());
            args.add(fe.getField());
            args.add(fe.getRejectedValue());
            if (null != fe.getArguments()) {
                for (Object o : fe.getArguments()) {
                    args.add(o);
                }
            }
            String msg = applicationContext.getMessage(fe.getCode(), args.toArray(), fe.getDefaultMessage(), null);            
            Map error = new HashMap<String, String>();
            error.put("field", fe.getField());
            error.put("message", msg);
            errors.add(error);
        }
        packet.put("errors", errors);

        return _negotiateResponse(request, HttpStatus.BAD_REQUEST, new HttpHeaders(), packet);
    }


	private ResponseEntity<byte[]> _negotiateResponse(final ServletServerHttpRequest request, final HttpStatus status, final HttpHeaders headers, final Object resource) throws IOException {
		Method negotiateResponseMethod = ReflectionUtils.findMethod( getClass(), "negotiateResponse", ServletServerHttpRequest.class, HttpStatus.class, HttpHeaders.class, Object.class );

		ReflectionUtils.makeAccessible( negotiateResponseMethod );

		try {
			return ( ResponseEntity<byte[]> ) negotiateResponseMethod.invoke( this, request, status, headers, resource );
		} catch ( InvocationTargetException ex ) {
			ReflectionUtils.rethrowRuntimeException( ex.getTargetException() );
			return null; // :)
		} catch ( IllegalAccessException ex ) {
			throw new UndeclaredThrowableException( ex );
		}
	}

	private ResponseEntity<?> negotiateResponse( ServletServerHttpRequest request, Page page, PagedResources.PageMetadata pageMetadata ) throws IOException {
		return _negotiateResponse( request, HttpStatus.OK, new HttpHeaders(), new PagedResources( toResources( page ), pageMetadata, Lists.<Link>newArrayList() ) );
	}

	private PagedResources.PageMetadata pageMetadata( final Page page ) {
		return new PagedResources.PageMetadata( page.getSize(), page.getNumber() + 1, page.getTotalElements(), page.getTotalPages() );
	}

	private List<Object> toResources( Page page ) {
		if ( !page.hasContent() ) {
			return newLinkedList();
		}

		List<Object> allResources = newArrayList();
		for ( final Object item : page ) {
			allResources.add( item );
		}
		return allResources;
	}

	@Override
	@Autowired
	public void setGlobalAdministrationConfiguration( final GlobalAdministrationConfiguration configuration ) {
		this.configuration = configuration;
	}

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        super.setApplicationContext(applicationContext);
        this.applicationContext = applicationContext;
    }
	
}